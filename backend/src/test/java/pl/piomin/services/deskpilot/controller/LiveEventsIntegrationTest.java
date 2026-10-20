package pl.piomin.services.deskpilot.controller;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.AfterEach;
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
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.ApprovalRequest;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.RunService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** {@code GET /api/events} over real HTTP: who receives which event when a run is paused, approved and finished. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class LiveEventsIntegrationTest {

    private static final Duration WAIT = Duration.ofSeconds(10);

    @MockitoBean
    private ChatModel chatModel;

    @MockitoBean
    private KnowledgeBaseService knowledgeBaseService;

    @MockitoBean
    private ModelCatalog modelCatalog;

    @LocalServerPort
    private int port;

    @Autowired
    private RestTestClient client;

    @Autowired
    private TestSecurityConfiguration.TestTokens tokens;

    @Autowired
    private RunService runService;

    private final HttpClient http = HttpClient.newHttpClient();

    @AfterEach
    void closeStreams() {
        http.shutdownNow();
    }

    @Test
    void ownerFollowsTheRunReviewersTheQueueAndOthersSeeNothing() throws Exception {
        EventStream alice = open("alice", "user");
        EventStream bob = open("bob", "user", "reviewer");
        EventStream carol = open("carol", "user");
        alice.next("connected");
        carol.next("connected");
        bob.next("connected");
        int pendingBefore = pending(bob.next("approvals"));

        when(chatModel.call(any(Prompt.class))).thenReturn(
                response(AssistantMessage.builder().content("").toolCalls(List.of(new AssistantMessage.ToolCall("c1",
                        "function", "create_ticket", "{\"title\":\"Docking station\",\"description\":\"Broken\",\"priority\":\"LOW\"}"))).build()),
                response(new AssistantMessage("Ticket created.")));
        String sessionId = as("alice", "user").post().uri("/api/sessions").exchange()
                .expectBody(SessionResponse.class).returnResult().getResponseBody().id();
        RunResponse paused = as("alice", "user").post().uri("/api/sessions/{id}/messages", sessionId)
                .body(new ChatRequest("My docking station is broken, open a ticket")).exchange()
                .expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(paused.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        assertThat(alice.next("run")).contains("\"id\":\"" + paused.id() + "\"").contains("\"status\":\"AWAITING_APPROVAL\"");
        assertThat(pending(bob.next("approvals"))).isEqualTo(pendingBefore + 1);

        as("bob", "user", "reviewer").post().uri("/api/runs/{id}/approval", paused.id())
                .body(new ApprovalRequest(true, null)).exchange()
                .expectStatus().isOk();

        assertThat(alice.next("ticket")).contains("Docking station").contains("\"requestedBy\":\"alice\"");
        assertThat(bob.next("ticket")).contains("Docking station");
        assertThat(alice.nextRunWithStatus(paused.id(), "COMPLETED")).contains("Ticket created.");
        assertThat(pending(bob.lastOf("approvals"))).isEqualTo(pendingBefore);

        assertThat(carol.received(Duration.ofMillis(500))).isEmpty();
    }

    @Test
    void evaluationRunsSendNoEvents() throws Exception {
        EventStream alice = open("alice", "user");
        alice.next("connected");
        String sessionId = as("alice", "user").post().uri("/api/sessions").exchange()
                .expectBody(SessionResponse.class).returnResult().getResponseBody().id();

        String runId = runService.start(sessionId, "alice", "scenario", "qwen3:8b", RunSource.EVALUATION).getId();
        runService.finish(runId, RunStatus.COMPLETED, "done", 0);

        assertThat(alice.received(Duration.ofMillis(500))).isEmpty();
    }

    @Test
    void eventsNeedAToken() throws Exception {
        HttpResponse<Void> response = http.send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                .header("Accept", "text/event-stream").build(), HttpResponse.BodyHandlers.discarding());

        assertThat(response.statusCode()).isEqualTo(401);
    }

    private EventStream open(String username, String... roles) {
        EventStream stream = new EventStream();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/api/events"))
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .header(HttpHeaders.ACCEPT, "text/event-stream")
                .build();
        http.sendAsync(request, HttpResponse.BodyHandlers.ofLines())
                .thenAccept(response -> response.body().forEach(stream::accept));
        return stream;
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate().defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles)).build();
    }

    private static int pending(String data) {
        return Integer.parseInt(data.replaceAll("\\D", ""));
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }

    /** Collects the named events of one connection: {@code [event, data]} pairs, comments (keep-alive) ignored. */
    private static final class EventStream {

        private final BlockingQueue<String[]> events = new LinkedBlockingQueue<>();
        private String name = "message";

        void accept(String line) {
            if (line.startsWith("event:")) {
                name = line.substring(6).strip();
            } else if (line.startsWith("data:")) {
                events.add(new String[] {name, line.substring(5).strip()});
            } else if (line.isEmpty()) {
                name = "message";
            }
        }

        /** Data of the next event with this name; other events before it are skipped. */
        String next(String eventName) throws InterruptedException {
            long deadline = System.nanoTime() + WAIT.toNanos();
            while (System.nanoTime() < deadline) {
                String[] event = events.poll(100, TimeUnit.MILLISECONDS);
                if (event != null && event[0].equals(eventName)) {
                    return event[1];
                }
            }
            throw new AssertionError("No '" + eventName + "' event within " + WAIT);
        }

        String nextRunWithStatus(String runId, String status) throws InterruptedException {
            while (true) {
                String data = next("run");
                if (data.contains("\"id\":\"" + runId + "\"") && data.contains("\"status\":\"" + status + "\"")) {
                    return data;
                }
            }
        }

        /** Data of the last event with this name received so far, waiting briefly for late ones. */
        String lastOf(String eventName) throws InterruptedException {
            String last = next(eventName);
            for (String[] event : received(Duration.ofMillis(300))) {
                if (event[0].equals(eventName)) {
                    last = event[1];
                }
            }
            return last;
        }

        List<String[]> received(Duration wait) throws InterruptedException {
            Thread.sleep(wait.toMillis());
            List<String[]> drained = new java.util.ArrayList<>();
            events.drainTo(drained);
            return drained;
        }
    }
}
