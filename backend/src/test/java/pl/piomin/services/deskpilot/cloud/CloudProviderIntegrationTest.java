package pl.piomin.services.deskpilot.cloud;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.AgentSettingsDto;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyDouble;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

/**
 * Cloud providers end to end (12s): the real {@link org.springframework.ai.openai.OpenAiChatModel} talks to a fake
 * OpenAI-compatible server standing in for Mistral, so the key handling, the routing and the agent loop are all real.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class CloudProviderIntegrationTest {

    private static final String KEY = "test-key-1234";
    private static final HttpServer FAKE_MISTRAL = startFakeMistral();
    private static final List<String> CHAT_REQUESTS = new CopyOnWriteArrayList<>();
    private static final List<String> AUTH_HEADERS = new CopyOnWriteArrayList<>();
    private static final AtomicInteger CHAT_CALLS = new AtomicInteger();

    @DynamicPropertySource
    static void fakeProvider(DynamicPropertyRegistry registry) {
        registry.add("agent.providers.mistral.base-url",
                () -> "http://localhost:" + FAKE_MISTRAL.getAddress().getPort() + "/v1");
    }

    @AfterAll
    static void stopFakeMistral() {
        FAKE_MISTRAL.stop(0);
    }

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private CloudProviderRepository repository;

    @Autowired
    private AgentSettingsService settingsService;

    private RestTestClient admin;
    private RestTestClient alice;

    @BeforeEach
    void setUp() {
        admin = as("admin", "user", "reviewer", "admin");
        alice = as("alice", "user");
        CHAT_REQUESTS.clear();
        AUTH_HEADERS.clear();
        CHAT_CALLS.set(0);
    }

    @AfterEach
    void tearDown() {
        settingsService.reset();
        repository.deleteAll();
    }

    @Test
    void keysAreStoredEncryptedAndNeverReturned() {
        alice.get().uri("/api/providers").exchange().expectStatus().isForbidden();
        admin.get().uri("/api/providers").exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[?(@.id == 'mistral')].keySource").isEqualTo(List.of("NONE"))
                .jsonPath("$[?(@.id == 'gemini')].label").isEqualTo(List.of("Google Gemini"));

        String body = admin.patch().uri("/api/providers/mistral").body(Map.of("enabled", true, "apiKey", KEY))
                .exchange()
                .expectStatus().isOk()
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(body).contains("\"keySource\":\"STORED\"", "\"keyHint\":\"1234\"", "\"available\":true").doesNotContain(KEY);
        assertThat(repository.findById("mistral").orElseThrow().getApiKeyEncrypted()).isNotBlank().doesNotContain(KEY);

        admin.post().uri("/api/providers/mistral/test").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.ok").isEqualTo(true).jsonPath("$.models").isEqualTo(1);
        admin.get().uri("/api/providers/mistral/models").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$[*].id").isEqualTo(List.of("mistral-small-latest"));

        admin.patch().uri("/api/providers/mistral").body(Map.of("removeKey", true)).exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.keySource").isEqualTo("NONE").jsonPath("$.available").isEqualTo(false);
        admin.get().uri("/api/audit?action=PROVIDER_KEY_SET").exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.items[0].details").isEqualTo("key ending in 1234");
    }

    @Test
    void theAgentRunsOnTheCloudModelWithToolCalls() {
        when(knowledgeBaseService.search(eq("vpn"), anyInt(), anyDouble(), anyBoolean()))
                .thenReturn(List.of(new KnowledgeBaseService.Hit(1, "VPN", "Switch to IPSec", 0.9)));
        enableMistral(KEY);
        AgentSettingsDto settings = admin.get().uri("/api/settings").exchange()
                .expectBody(AgentSettingsDto.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        admin.put().uri("/api/settings").body(withCloudModel(settings, "mistral", "mistral-small-latest"))
                .exchange()
                .expectStatus().isOk()
                .expectBody().jsonPath("$.chatProvider").isEqualTo("mistral");

        RunResponse run = chat(alice, "My VPN keeps dropping");

        assertThat(run.status()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.model()).isEqualTo("mistral:mistral-small-latest");
        assertThat(run.answer()).isEqualTo("Switch the VPN protocol to IPSec [1].");
        assertThat(CHAT_CALLS.get()).isEqualTo(2);
        assertThat(AUTH_HEADERS).allMatch(h -> h.equals("Bearer " + KEY));
        assertThat(CHAT_REQUESTS.getFirst()).contains("\"model\":\"mistral-small-latest\"", "search_knowledge_base");
        // The second call carries the tool result back to the model.
        assertThat(CHAT_REQUESTS.get(1)).contains("Switch to IPSec");
    }

    @Test
    void aRejectedKeyIsReportedClearly() {
        enableMistral(KEY);
        AgentSettingsDto settings = admin.get().uri("/api/settings").exchange()
                .expectBody(AgentSettingsDto.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        admin.put().uri("/api/settings").body(withCloudModel(settings, "mistral", "mistral-small-latest")).exchange()
                .expectStatus().isOk();
        admin.patch().uri("/api/providers/mistral").body(Map.of("apiKey", "revoked-key-0000")).exchange().expectStatus().isOk();

        SessionResponse session = alice.post().uri("/api/sessions").exchange()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        alice.post().uri("/api/sessions/{id}/messages", session.id()).body(new ChatRequest("Hello"))
                .exchange()
                .expectStatus().isEqualTo(HttpStatus.BAD_GATEWAY)
                .expectBody()
                .jsonPath("$.detail").isEqualTo("Mistral rejected the API key. An admin can update it in Settings > Providers.");
    }

    @Test
    void settingsRefuseUnavailableProvidersAndUnknownModels() {
        AgentSettingsDto settings = admin.get().uri("/api/settings").exchange()
                .expectBody(AgentSettingsDto.class).returnResult().getResponseBody();
        assertThat(settings).isNotNull();
        admin.put().uri("/api/settings").body(withCloudModel(settings, "mistral", "mistral-small-latest")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(d -> assertThat((String) d).contains("not enabled or has no API key"));

        enableMistral(KEY);
        admin.put().uri("/api/settings").body(withCloudModel(settings, "mistral", "gpt-5")).exchange()
                .expectStatus().isBadRequest()
                .expectBody().jsonPath("$.detail").value(d -> assertThat((String) d).contains("not offered by mistral"));
    }

    private void enableMistral(String key) {
        admin.patch().uri("/api/providers/mistral").body(Map.of("enabled", true, "apiKey", key)).exchange()
                .expectStatus().isOk();
    }

    private RunResponse chat(RestTestClient user, String message) {
        SessionResponse session = user.post().uri("/api/sessions").exchange()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        RunResponse run = user.post().uri("/api/sessions/{id}/messages", session.id()).body(new ChatRequest(message))
                .exchange()
                .expectStatus().isOk()
                .expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(run).isNotNull();
        return run;
    }

    private static AgentSettingsDto withCloudModel(AgentSettingsDto s, String provider, String model) {
        return new AgentSettingsDto(model, s.temperature(), s.topP(), s.contextWindow(), s.maxOutputTokens(), false,
                s.maxIterations(), s.ragTopK(), s.ragSimilarityThreshold(), s.groundingCheck(), s.hybridSearch(),
                s.selfCheck(), null, provider, null, s.disabledTools(), s.approvalTools(), s.systemPrompt(), s.specialists(), null);
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles)).build();
    }

    // ---- a minimal OpenAI-compatible server ------------------------------------------------------------------

    private static HttpServer startFakeMistral() {
        try {
            HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
            server.createContext("/v1/models", exchange -> {
                if (!authorized(exchange)) {
                    return;
                }
                respond(exchange, 200, """
                        {"object":"list","data":[
                          {"id":"mistral-small-latest","capabilities":{"completion_chat":true,"function_calling":true,"vision":true}},
                          {"id":"mistral-embed","capabilities":{"completion_chat":false,"function_calling":false}}]}""");
            });
            server.createContext("/v1/chat/completions", exchange -> {
                String request = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                if (!authorized(exchange)) {
                    return;
                }
                CHAT_REQUESTS.add(request);
                AUTH_HEADERS.add(exchange.getRequestHeaders().getFirst("Authorization"));
                boolean first = CHAT_CALLS.incrementAndGet() % 2 == 1;
                respond(exchange, 200, first ? """
                        {"id":"c1","object":"chat.completion","created":1,"model":"mistral-small-latest",
                         "choices":[{"index":0,"finish_reason":"tool_calls","message":{"role":"assistant","content":null,
                           "tool_calls":[{"id":"call_1","type":"function",
                             "function":{"name":"search_knowledge_base","arguments":"{\\"query\\":\\"vpn\\"}"}}]}}],
                         "usage":{"prompt_tokens":50,"completion_tokens":10,"total_tokens":60}}""" : """
                        {"id":"c2","object":"chat.completion","created":1,"model":"mistral-small-latest",
                         "choices":[{"index":0,"finish_reason":"stop",
                           "message":{"role":"assistant","content":"Switch the VPN protocol to IPSec [1]."}}],
                         "usage":{"prompt_tokens":80,"completion_tokens":12,"total_tokens":92}}""");
            });
            server.start();
            return server;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static boolean authorized(HttpExchange exchange) throws IOException {
        if (("Bearer " + KEY).equals(exchange.getRequestHeaders().getFirst("Authorization"))) {
            return true;
        }
        respond(exchange, 401, """
                {"error":{"message":"Invalid API key","type":"invalid_request_error","code":"invalid_api_key"}}""");
        return false;
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
