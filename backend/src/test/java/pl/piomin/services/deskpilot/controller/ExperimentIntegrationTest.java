package pl.piomin.services.deskpilot.controller;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.ExperimentResponse;
import pl.piomin.services.deskpilot.dto.FeedbackRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.model.ExperimentStatus;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.repository.ExperimentRepository;
import pl.piomin.services.deskpilot.repository.PromptVersionRepository;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Prompt versions and A/B experiments (12m): conversations are split between two variants, each run uses its
 * variant's model and prompt, and the results compare the variants.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class ExperimentIntegrationTest {

    private static final String MARKER = "You are the variant-B helpdesk assistant.";

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private ExperimentRepository experimentRepository;

    @Autowired
    private PromptVersionRepository promptRepository;

    /** System prompt sent to the model, per model name. */
    private final Map<String, String> instructionsByModel = new ConcurrentHashMap<>();

    private RestTestClient admin;
    private RestTestClient alice;

    @BeforeEach
    void setUp() {
        admin = as("admin", "user", "reviewer", "admin");
        alice = as("alice", "user");
        for (String model : List.of("qwen3:8b", "llama3.2:3b")) {
            when(modelCatalog.find(model)).thenReturn(Optional.of(new ModelCatalog.ModelInfo(model, 1, "3B", "llama",
                    List.of("completion", "tools"), true, false, true)));
        }
        when(chatModel.call(any(Prompt.class))).thenAnswer(invocation -> {
            Prompt prompt = invocation.getArgument(0);
            instructionsByModel.put(prompt.getOptions().getModel(),
                    prompt.getInstructions().stream().map(Message::getText).reduce("", String::concat));
            return new ChatResponse(List.of(new Generation(new AssistantMessage("Restart the laptop."))));
        });
    }

    @AfterEach
    void tearDown() {
        experimentRepository.deleteAll();
        promptRepository.deleteAll();
    }

    @Test
    void promptVersionsAreAdminOnlyWithUniqueNames() {
        alice.get().uri("/api/prompts").exchange().expectStatus().isForbidden();
        alice.get().uri("/api/experiments").exchange().expectStatus().isForbidden();

        admin.post().uri("/api/prompts").body(Map.of("name", "v2", "content", MARKER)).exchange()
                .expectStatus().isCreated()
                .expectBody().jsonPath("$.name").isEqualTo("v2").jsonPath("$.createdBy").isEqualTo("admin");
        admin.post().uri("/api/prompts").body(Map.of("name", "v2", "content", "other")).exchange()
                .expectStatus().isEqualTo(409);
        admin.post().uri("/api/prompts").body(Map.of("name", "", "content", "x")).exchange()
                .expectStatus().isBadRequest();
        admin.get().uri("/api/prompts").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[*].name").isEqualTo(List.of("v2"));
    }

    @Test
    void conversationsAreSplitBetweenVariantsAndResultsCompareThem() {
        long promptId = createPrompt("concise-v2", MARKER);
        ExperimentResponse started = admin.post().uri("/api/experiments")
                .body(Map.of("name", "llama vs qwen",
                        "a", Map.of("model", "qwen3:8b"),
                        "b", Map.of("provider", "ollama", "model", "llama3.2:3b", "promptId", promptId),
                        "bPercent", 50))
                .exchange()
                .expectStatus().isCreated()
                .expectBody(ExperimentResponse.class).returnResult().getResponseBody();
        assertThat(started).isNotNull();
        assertThat(started.status()).isEqualTo(ExperimentStatus.RUNNING);
        assertThat(started.variants()).extracting(ExperimentResponse.VariantResult::promptVersionName)
                .containsExactly(null, "concise-v2");

        // Only one experiment at a time; a prompt version in use cannot be deleted.
        admin.post().uri("/api/experiments")
                .body(Map.of("name", "other", "a", Map.of("model", "qwen3:8b"), "b", Map.of("model", "llama3.2:3b"), "bPercent", 10))
                .exchange().expectStatus().isEqualTo(409);
        admin.delete().uri("/api/prompts/{id}", promptId).exchange().expectStatus().isEqualTo(409);

        // New conversations until both variants were used (a session keeps its variant).
        Map<String, RunResponse> firstRunByModel = new HashMap<>();
        int conversations = 0;
        while (firstRunByModel.size() < 2 && conversations < 60) {
            String sessionId = newSession();
            RunResponse first = chat(sessionId, "My laptop is slow");
            RunResponse second = chat(sessionId, "Still slow");
            assertThat(second.model()).isEqualTo(first.model());
            firstRunByModel.putIfAbsent(first.model(), first);
            conversations++;
        }
        assertThat(firstRunByModel).containsOnlyKeys("qwen3:8b", "llama3.2:3b");
        assertThat(instructionsByModel.get("llama3.2:3b")).contains(MARKER);
        assertThat(instructionsByModel.get("qwen3:8b")).doesNotContain(MARKER);

        alice.put().uri("/api/runs/{id}/feedback", firstRunByModel.get("llama3.2:3b").id())
                .body(new FeedbackRequest(FeedbackRating.UP, null)).exchange()
                .expectStatus().isOk();

        ExperimentResponse results = admin.get().uri("/api/experiments/{id}", started.id()).exchange()
                .expectStatus().isOk()
                .expectBody(ExperimentResponse.class).returnResult().getResponseBody();
        assertThat(results).isNotNull();
        ExperimentResponse.VariantResult a = results.variants().get(0);
        ExperimentResponse.VariantResult b = results.variants().get(1);
        assertThat(a.runs() + b.runs()).isEqualTo(2L * conversations);
        assertThat(a.finished()).isEqualTo(a.runs());
        assertThat(a.successRate()).isEqualTo(1.0);
        assertThat(b.successRate()).isEqualTo(1.0);
        assertThat(b.up()).isEqualTo(1);
        assertThat(b.satisfactionRate()).isEqualTo(1.0);
        assertThat(a.up() + a.down()).isZero();

        // Stopped: chats go back to the model of the settings.
        admin.post().uri("/api/experiments/{id}/stop", started.id()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.status").isEqualTo("STOPPED").jsonPath("$.stoppedBy").isEqualTo("admin");
        admin.post().uri("/api/experiments/{id}/stop", started.id()).exchange().expectStatus().isEqualTo(409);
        for (int i = 0; i < 4; i++) {
            assertThat(chat(newSession(), "Hello").model()).isEqualTo("qwen3:8b");
        }
        admin.get().uri("/api/experiments/{id}", started.id()).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.variants[0].runs").isEqualTo((int) a.runs());
        admin.get().uri("/api/audit?action=EXPERIMENT_STARTED").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].details")
                .isEqualTo("llama vs qwen: A=qwen3:8b, B=llama3.2:3b + prompt #" + promptId + " (50% to B)");
    }

    @Test
    void invalidExperimentsAreRejected() {
        when(modelCatalog.find("missing")).thenReturn(Optional.empty());
        admin.post().uri("/api/experiments")
                .body(Map.of("name", "same", "a", Map.of("model", "qwen3:8b"), "b", Map.of("model", "qwen3:8b"), "bPercent", 50))
                .exchange().expectStatus().isBadRequest();
        admin.post().uri("/api/experiments")
                .body(Map.of("name", "missing", "a", Map.of("model", "qwen3:8b"), "b", Map.of("model", "missing"), "bPercent", 50))
                .exchange().expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(String.class, detail -> assertThat(detail).contains("ollama pull missing"));
        admin.post().uri("/api/experiments")
                .body(Map.of("name", "split", "a", Map.of("model", "qwen3:8b"), "b", Map.of("model", "llama3.2:3b"), "bPercent", 100))
                .exchange().expectStatus().isBadRequest();
        admin.post().uri("/api/experiments")
                .body(Map.of("name", "cloud", "a", Map.of("model", "qwen3:8b"),
                        "b", Map.of("provider", "mistral", "model", "mistral-small-latest"), "bPercent", 50))
                .exchange().expectStatus().isBadRequest();
        assertThat(experimentRepository.count()).isZero();
    }

    private long createPrompt(String name, String content) {
        PromptController.PromptVersionResponse version = admin.post().uri("/api/prompts")
                .body(Map.of("name", name, "content", content)).exchange()
                .expectStatus().isCreated()
                .expectBody(PromptController.PromptVersionResponse.class).returnResult().getResponseBody();
        assertThat(version).isNotNull();
        return version.id();
    }

    private String newSession() {
        SessionResponse session = alice.post().uri("/api/sessions").exchange()
                .expectStatus().isCreated()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        return session.id();
    }

    private RunResponse chat(String sessionId, String message) {
        RunResponse run = alice.post().uri("/api/sessions/{id}/messages", sessionId).body(new ChatRequest(message)).exchange()
                .expectStatus().isOk()
                .expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(run).isNotNull();
        return run;
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }
}
