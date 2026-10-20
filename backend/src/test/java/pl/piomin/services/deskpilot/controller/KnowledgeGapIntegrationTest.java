package pl.piomin.services.deskpilot.controller;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.ArticleResponse;
import pl.piomin.services.deskpilot.dto.KnowledgeGapResponse;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.GapReason;
import pl.piomin.services.deskpilot.model.KnowledgeGapStatus;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.FakeEmbeddingConfiguration;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 12d over HTTP: unanswered questions (escalated, ticket, 👎, empty searches) are grouped into a gap, the model drafts
 * an article, a reviewer publishes it into the searchable knowledge base. Embeddings are bag-of-words fakes, so the
 * grouping threshold is lowered.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.knowledge-gaps.similarity=0.5", "agent.knowledge-gaps.cron=-"})
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class, FakeEmbeddingConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class KnowledgeGapIntegrationTest {

    private static final String GAPS = "/api/knowledge-gaps";
    private static final String NO_RESULTS = "{\"articles\":[],\"guidance\":\"No relevant article found. Try once more\"}";

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private RunFeedbackRepository feedbackRepository;

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Autowired
    private AuditService auditService;

    private RestTestClient alice;
    private RestTestClient bob;

    @BeforeEach
    void setUp() {
        alice = as("alice", "user");
        bob = as("bob", "user", "reviewer");
    }

    @Test
    void unansweredQuestionsBecomeAGapThatIsDraftedAndPublished() {
        String escalated = run("Jabra headset pairing with Teams fails", RunStatus.ESCALATED);
        String ticket = run("Teams does not see my Jabra headset after pairing", RunStatus.COMPLETED);
        step(ticket, StepType.TOOL_CALL, "create_ticket", "{}");
        String notFound = run("Jabra headset pairing Teams microphone silent", RunStatus.COMPLETED);
        step(notFound, StepType.TOOL_RESULT, "search_knowledge_base", NO_RESULTS);
        String alone = run("Where is the canteen menu published?", RunStatus.COMPLETED);
        feedbackRepository.save(new RunFeedback(alone, "alice", FeedbackRating.DOWN, "useless"));
        String answered = run("Jabra headset pairing with Teams", RunStatus.COMPLETED);
        step(answered, StepType.TOOL_RESULT, "search_knowledge_base", NO_RESULTS);
        step(answered, StepType.TOOL_RESULT, "search_knowledge_base", "{\"articles\":[{\"id\":1}]}");

        alice.post().uri(GAPS + "/detect").exchange().expectStatus().isForbidden();
        bob.post().uri(GAPS + "/detect").exchange().expectStatus().isOk();

        KnowledgeGapResponse gap = gapWith(escalated).orElseThrow();
        assertThat(gap.status()).isEqualTo(KnowledgeGapStatus.OPEN);
        assertThat(gap.questions()).extracting(KnowledgeGapResponse.Question::runId)
                .containsExactlyInAnyOrder(escalated, ticket, notFound);
        assertThat(gap.reasons()).containsExactlyInAnyOrderEntriesOf(
                Map.of(GapReason.ESCALATED, 1L, GapReason.TICKET, 1L, GapReason.NOT_FOUND, 1L));
        assertThat(gapWith(alone)).as("a single question is not a gap yet").isEmpty();
        assertThat(gapWith(answered)).as("a later search found an article").isEmpty();

        // A new similar question joins the pending gap; detecting again never counts a question twice.
        String later = run("Jabra headset not pairing with Teams", RunStatus.ESCALATED);
        bob.post().uri(GAPS + "/detect").exchange().expectStatus().isOk();
        assertThat(gapWith(later)).hasValueSatisfying(g -> {
            assertThat(g.id()).isEqualTo(gap.id());
            assertThat(g.questionCount()).isEqualTo(4);
        });

        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage("""
                <think>Headset questions.</think>
                TITLE: Pair a Jabra headset with Teams
                TAGS: Jabra, headset, Teams

                Jabra headset pairing with Teams: select the headset as the Teams audio device.
                1. Open Teams settings.""")))));
        KnowledgeGapResponse drafted = bob.post().uri(GAPS + "/{id}/draft", gap.id()).exchange()
                .expectStatus().isOk().expectBody(KnowledgeGapResponse.class).returnResult().getResponseBody();
        assertThat(drafted).isNotNull();
        assertThat(drafted.status()).isEqualTo(KnowledgeGapStatus.DRAFTED);
        assertThat(drafted.draftTitle()).isEqualTo("Pair a Jabra headset with Teams");
        assertThat(drafted.draftTags()).isEqualTo("jabra, headset, teams");
        assertThat(drafted.draftContent()).startsWith("Jabra headset pairing").doesNotContain("TITLE", "<think>");
        assertThat(drafted.draftedBy()).as("model label of the current settings").isNotBlank().isNotEqualTo("bob");
        ArgumentCaptor<Prompt> prompt = ArgumentCaptor.forClass(Prompt.class);
        verify(chatModel).call(prompt.capture());
        assertThat(prompt.getValue().getUserMessage().getText()).contains("- Teams does not see my Jabra headset after pairing");

        bob.put().uri(GAPS + "/{id}/draft", gap.id()).body(Map.of("title", " ", "content", "x")).exchange()
                .expectStatus().isBadRequest();
        bob.put().uri(GAPS + "/{id}/draft", gap.id())
                .body(Map.of("title", "Jabra headset pairing with Teams", "content", drafted.draftContent() + "\n2. Pick the Jabra headset.",
                        "tags", "jabra,headset,teams,pairing"))
                .exchange().expectStatus().isOk();

        KnowledgeGapResponse published = bob.post().uri(GAPS + "/{id}/publish", gap.id()).exchange()
                .expectStatus().isOk().expectBody(KnowledgeGapResponse.class).returnResult().getResponseBody();
        assertThat(published).isNotNull();
        assertThat(published.status()).isEqualTo(KnowledgeGapStatus.PUBLISHED);
        assertThat(published.decidedBy()).isEqualTo("bob");
        assertThat(published.articleId()).isNotNull();
        bob.post().uri(GAPS + "/{id}/publish", gap.id()).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);

        List<ArticleResponse> articles = alice.get().uri("/api/knowledge-base/articles").exchange().expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<ArticleResponse>>() { }).returnResult().getResponseBody();
        assertThat(articles).anySatisfy(a -> {
            assertThat(a.id()).isEqualTo(published.articleId());
            assertThat(a.content()).contains("Pick the Jabra headset");
        });
        assertThat(knowledgeBaseService.search("Jabra headset pairing Teams", 3, 0.5, false))
                .extracting(KnowledgeBaseService.Hit::articleId).contains(published.articleId());

        // The article now answers the topic: a new question about it is not a gap any more.
        String answeredNow = run("Jabra headset pairing with Teams", RunStatus.ESCALATED);
        KnowledgeGapController.DetectionResponse detection = bob.post().uri(GAPS + "/detect").exchange()
                .expectStatus().isOk().expectBody(KnowledgeGapController.DetectionResponse.class).returnResult().getResponseBody();
        assertThat(detection).isNotNull();
        assertThat(detection.covered()).isPositive();
        assertThat(gapWith(answeredNow)).isEmpty();

        assertThat(auditService.search(new AuditService.Filter(AuditAction.KNOWLEDGE_GAP_PUBLISHED, "bob", null, null), 0, 10)
                .getContent()).anySatisfy(e -> assertThat(e.getTargetId()).isEqualTo(String.valueOf(gap.id())));
    }

    @Test
    void reviewersDismissAGapAndDeletedConversationsLeaveIt() {
        String first = run("Parking badge for the Lyon garage expired", RunStatus.ESCALATED);
        String second = run("Lyon garage parking badge does not open the gate", RunStatus.ESCALATED);
        bob.post().uri(GAPS + "/detect").exchange().expectStatus().isOk();
        KnowledgeGapResponse gap = gapWith(first).orElseThrow();
        assertThat(gap.questionCount()).isEqualTo(2);

        runRepository.deleteById(second);
        assertThat(gapWith(first)).hasValueSatisfying(g -> assertThat(g.questionCount()).isEqualTo(1));

        bob.post().uri(GAPS + "/{id}/publish", gap.id()).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        KnowledgeGapResponse dismissed = bob.post().uri(GAPS + "/{id}/dismiss", gap.id()).exchange()
                .expectStatus().isOk().expectBody(KnowledgeGapResponse.class).returnResult().getResponseBody();
        assertThat(dismissed).isNotNull();
        assertThat(dismissed.status()).isEqualTo(KnowledgeGapStatus.DISMISSED);
        bob.post().uri(GAPS + "/{id}/draft", gap.id()).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        bob.post().uri(GAPS + "/{id}/dismiss", 999_999).exchange().expectStatus().isNotFound();

        // Dismissed questions are not offered again.
        bob.post().uri(GAPS + "/detect").exchange().expectStatus().isOk();
        assertThat(gapWith(first)).hasValueSatisfying(g -> assertThat(g.status()).isEqualTo(KnowledgeGapStatus.DISMISSED));
    }

    private Optional<KnowledgeGapResponse> gapWith(String runId) {
        List<KnowledgeGapResponse> gaps = bob.get().uri(GAPS).exchange().expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<List<KnowledgeGapResponse>>() { }).returnResult().getResponseBody();
        assertThat(gaps).isNotNull();
        // Only the 5 most recent questions are listed; the tests keep gaps smaller than that.
        return gaps.stream().filter(g -> g.questions().stream().anyMatch(q -> q.runId().equals(runId))).findFirst();
    }

    private String run(String question, RunStatus status) {
        AgentRun run = new AgentRun(UUID.randomUUID().toString(), "alice", question, "qwen3:8b");
        run.finish(status, "Sorry.");
        return runRepository.save(run).getId();
    }

    private void step(String runId, StepType type, String name, String content) {
        stepRepository.save(new AgentStep(runId, type, name, content, false, 1));
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }
}
