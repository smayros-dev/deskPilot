package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureRestTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.exception.ModelUnavailableException;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** Rate limits per user (2 messages per minute here) and the 503 of an open circuit breaker, over HTTP. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"agent.rate-limit.per-minute=2", "agent.rate-limit.per-day=0"})
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class GuardrailsV2IntegrationTest {

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

    @Test
    void messagesOverTheLimitGet429WithRetryAfterPerUserAndAdminsAreNotLimited() {
        givenModelAnswers("Hello!");
        RestTestClient dave = as("dave", "user");
        String session = newSession(dave);

        send(dave, session).expectStatus().isOk().expectHeader().valueEquals("X-RateLimit-Remaining", "1");
        send(dave, session).expectStatus().isOk();
        send(dave, session)
                .expectStatus().isEqualTo(HttpStatus.TOO_MANY_REQUESTS)
                .expectHeader().exists(HttpHeaders.RETRY_AFTER)
                .expectBody()
                .jsonPath("$.title").isEqualTo("Too many requests")
                .jsonPath("$.detail").value(d -> assertThat((String) d).startsWith("You are sending messages too fast"));
        // Reading is not limited.
        dave.get().uri("/api/sessions/{id}/runs", session).exchange().expectStatus().isOk();

        RestTestClient erin = as("erin", "user");
        send(erin, newSession(erin)).expectStatus().isOk();

        RestTestClient admin = as("admin", "user", "reviewer", "admin");
        String adminSession = newSession(admin);
        for (int i = 0; i < 3; i++) {
            send(admin, adminSession).expectStatus().isOk();
        }
    }

    @Test
    void openCircuitBreakerAnswers503WithRetryAfter() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new ModelUnavailableException(30));
        RestTestClient frank = as("frank", "user");

        send(frank, newSession(frank))
                .expectStatus().isEqualTo(HttpStatus.SERVICE_UNAVAILABLE)
                .expectHeader().valueEquals(HttpHeaders.RETRY_AFTER, "30")
                .expectBody()
                .jsonPath("$.title").isEqualTo("Model unavailable")
                .jsonPath("$.retryAfterSeconds").isEqualTo(30);
    }

    private RestTestClient.ResponseSpec send(RestTestClient user, String sessionId) {
        return user.post().uri("/api/sessions/{id}/messages", sessionId).body(new ChatRequest("Hi")).exchange();
    }

    private String newSession(RestTestClient user) {
        SessionResponse session = user.post().uri("/api/sessions").exchange()
                .expectStatus().isCreated()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        return session.id();
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }

    private void givenModelAnswers(String text) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(text))),
                ChatResponseMetadata.builder().usage(new DefaultUsage(10, 5)).build()));
    }
}
