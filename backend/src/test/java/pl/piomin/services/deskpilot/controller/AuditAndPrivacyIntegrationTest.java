package pl.piomin.services.deskpilot.controller;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.memory.ChatMemory;
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
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.client.RestTestClient;
import org.springframework.transaction.support.TransactionTemplate;
import pl.piomin.services.deskpilot.TestSecurityConfiguration;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.dto.ApprovalRequest;
import pl.piomin.services.deskpilot.dto.AuditEventResponse;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.dto.UserDataExport;
import pl.piomin.services.deskpilot.model.MemoryFact;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentSessionRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;
import pl.piomin.services.deskpilot.repository.MemoryFactRepository;
import pl.piomin.services.deskpilot.repository.PendingTurnRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;
import pl.piomin.services.deskpilot.repository.TicketRepository;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.MemoryService;
import pl.piomin.services.deskpilot.service.PrivacyService;
import pl.piomin.services.deskpilot.service.TicketService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/** 12o over HTTP with signed JWTs: the audit trail (admins only) and the users' own data (export, delete). */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class AuditAndPrivacyIntegrationTest {

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
    private MemoryService memoryService;

    @Autowired
    private TicketService ticketService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private AgentSessionRepository sessionRepository;

    @Autowired
    private MemoryFactRepository memoryRepository;

    @Autowired
    private TicketRepository ticketRepository;

    @Autowired
    private RunFeedbackRepository feedbackRepository;

    @Autowired
    private PendingTurnRepository pendingTurnRepository;

    @Autowired
    private ChatMemory chatMemory;

    @Autowired
    private AuditService auditService;

    @Autowired
    private JdbcClient jdbcClient;

    @Autowired
    private TransactionTemplate transactionTemplate;

    private RestTestClient alice;
    private RestTestClient carol;
    private RestTestClient bob;
    private RestTestClient admin;

    @BeforeEach
    void setUp() {
        alice = as("alice", "user");
        carol = as("carol", "user");
        bob = as("bob", "user", "reviewer");
        admin = as("admin", "user", "reviewer", "admin");
    }

    @Test
    void approvalsAndSettingsChangesAreAudited() {
        String session = newSession(alice);
        givenModelReplies(
                toolCall("create_ticket", "{\"title\":\"Dock\",\"description\":\"Broken dock\",\"priority\":\"LOW\"}"),
                answer("Ticket created."));
        RunResponse paused = chat(alice, session, "Open a ticket for my dock").expectBody(RunResponse.class)
                .returnResult().getResponseBody();
        assertThat(paused).isNotNull();
        bob.post().uri("/api/runs/{id}/approval", paused.id()).body(new ApprovalRequest(true, "fine"))
                .exchange().expectStatus().isOk();

        assertThat(audit("action=APPROVAL_APPROVED&actor=bob").items()).anySatisfy(e -> {
            assertThat(e.targetId()).isEqualTo(paused.id());
            assertThat(e.details()).contains("create_ticket", "Dock", "comment: fine");
        });

        Map<String, Object> settings = settingsAsAdmin();
        when(modelCatalog.find((String) settings.get("chatModel"))).thenReturn(Optional.of(new ModelCatalog.ModelInfo(
                (String) settings.get("chatModel"), 1, "8B", "qwen3", List.of("completion", "tools"), true, false, true)));
        Map<String, Object> changed = new HashMap<>(settings);
        changed.put("temperature", 0.9);
        admin.put().uri("/api/settings").body(changed).exchange().expectStatus().isOk();
        admin.delete().uri("/api/settings").exchange().expectStatus().isOk();

        assertThat(audit("action=SETTINGS_CHANGED&actor=admin").items()).first()
                .satisfies(e -> assertThat(e.details()).isEqualTo("temperature: " + settings.get("temperature") + " → 0.9"));
        assertThat(audit("action=SETTINGS_RESET").items()).first()
                .satisfies(e -> assertThat(e.details()).contains("temperature: 0.9 → " + settings.get("temperature")));
    }

    @Test
    void auditTrailIsForAdminsAndExportsAsCsv() {
        auditService.record("admin", pl.piomin.services.deskpilot.model.AuditAction.EVALUATION_CANCELLED, "evaluation",
                "e-1", "=cmd, \"quoted\"");

        alice.get().uri("/api/audit").exchange().expectStatus().isForbidden();
        bob.get().uri("/api/audit").exchange().expectStatus().isForbidden();
        bob.get().uri("/api/audit/export").exchange().expectStatus().isForbidden();

        String csv = admin.get().uri("/api/audit/export?action=EVALUATION_CANCELLED").exchange()
                .expectStatus().isOk()
                .expectHeader().contentTypeCompatibleWith(new MediaType("text", "csv"))
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment;.*\\.csv\"")
                .expectBody(String.class).returnResult().getResponseBody();
        assertThat(csv).startsWith("at,actor,action,target_type,target_id,details\n")
                .contains(",admin,EVALUATION_CANCELLED,evaluation,e-1,\"'=cmd, \"\"quoted\"\"\"");
        admin.get().uri("/api/audit?action=NOT_AN_ACTION").exchange().expectStatus().isBadRequest();
    }

    @Test
    void deletingAConversationRemovesItsRunsTraceFeedbackAndMemory() {
        String session = newSession(alice);
        givenModelReplies(answer("Restart the router."));
        RunResponse run = chat(alice, session, "Wi-Fi is slow").expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(run).isNotNull();
        alice.put().uri("/api/runs/{id}/feedback", run.id()).body(Map.of("rating", "UP")).exchange().expectStatus().isOk();
        assertThat(chatMemory.get(session)).isNotEmpty();

        carol.delete().uri("/api/sessions/{id}", session).exchange().expectStatus().isNotFound();
        alice.delete().uri("/api/sessions/{id}", session).exchange().expectStatus().isNoContent();

        alice.get().uri("/api/sessions/{id}", session).exchange().expectStatus().isNotFound();
        assertThat(runRepository.findById(run.id())).isEmpty();
        assertThat(stepRepository.findByRunIdOrderByIdAsc(run.id())).isEmpty();
        assertThat(feedbackRepository.findByRunId(run.id())).isEmpty();
        assertThat(chatMemory.get(session)).isEmpty();
        assertThat(audit("action=CONVERSATION_DELETED&actor=alice").items())
                .anySatisfy(e -> assertThat(e.targetId()).isEqualTo(session));
    }

    @Test
    void conversationWaitingForApprovalCannotBeDeleted() {
        String session = newSession(alice);
        givenModelReplies(toolCall("create_ticket", "{\"title\":\"Chair\",\"description\":\"Broken\",\"priority\":\"LOW\"}"));
        RunResponse paused = chat(alice, session, "Open a ticket for my chair").expectBody(RunResponse.class)
                .returnResult().getResponseBody();
        assertThat(paused).isNotNull();
        assertThat(paused.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        alice.delete().uri("/api/sessions/{id}", session).exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        alice.delete().uri("/api/me/data").exchange().expectStatus().isEqualTo(HttpStatus.CONFLICT);
        assertThat(runRepository.findById(paused.id())).isPresent();
        assertThat(pendingTurnRepository.existsById(paused.id())).isTrue();

        bob.post().uri("/api/runs/{id}/approval", paused.id()).body(new ApprovalRequest(false, null)).exchange()
                .expectStatus().isOk();
    }

    @Test
    void usersDeleteOnlyTheirOwnMemories() {
        MemoryFact mac = memoryService.save("dora", "Works on a MacBook");
        memoryService.save("dora", "Prefers e-mail");
        MemoryFact other = memoryService.save("carol", "Uses Linux");
        RestTestClient dora = as("dora", "user");

        dora.delete().uri("/api/me/memories/{id}", other.getId()).exchange().expectStatus().isNotFound();
        dora.delete().uri("/api/me/memories/{id}", mac.getId()).exchange().expectStatus().isNoContent();
        assertThat(memoryRepository.findByUserIdOrderByCreatedAtDesc("dora")).extracting(MemoryFact::getContent)
                .containsExactly("Prefers e-mail");

        dora.delete().uri("/api/me/memories").exchange().expectStatus().isNoContent();
        assertThat(memoryRepository.findByUserIdOrderByCreatedAtDesc("dora")).isEmpty();
        assertThat(memoryRepository.findById(other.getId())).isPresent();
    }

    @Test
    void exportContainsTheCallersDataOnly() {
        RestTestClient erin = as("erin", "user");
        String session = newSession(erin);
        givenModelReplies(answer("Use the Company Portal."));
        chat(erin, session, "How do I install software?").expectStatus().isOk();
        memoryService.save("erin", "Has a ThinkPad");
        memoryService.save("frank", "Has a Surface");
        ticketService.create("Monitor", "Second screen", TicketPriority.LOW, "erin");
        ticketService.create("Keyboard", "Broken", TicketPriority.LOW, "frank");

        UserDataExport export = erin.get().uri("/api/me/export").exchange()
                .expectStatus().isOk()
                .expectHeader().valueMatches(HttpHeaders.CONTENT_DISPOSITION, "attachment;.*\\.json\"")
                .expectBody(UserDataExport.class).returnResult().getResponseBody();

        assertThat(export).isNotNull();
        assertThat(export.userId()).isEqualTo("erin");
        assertThat(export.conversations()).singleElement().satisfies(c -> assertThat(c.runs()).singleElement()
                .satisfies(r -> {
                    assertThat(r.question()).isEqualTo("How do I install software?");
                    assertThat(r.answer()).isEqualTo("Use the Company Portal.");
                }));
        assertThat(export.memories()).extracting(UserDataExport.Memory::content).containsExactly("Has a ThinkPad");
        assertThat(export.tickets()).extracting(UserDataExport.TicketRequested::title).containsExactly("Monitor");
    }

    @Test
    void deletingAllMyDataKeepsTheTickets() {
        RestTestClient gina = as("gina", "user");
        String session = newSession(gina);
        givenModelReplies(answer("Hello!"));
        chat(gina, session, "Hi").expectStatus().isOk();
        memoryService.save("gina", "Works remotely");
        long ticket = ticketService.create("Headset", "Broken", TicketPriority.LOW, "gina").getId();

        gina.delete().uri("/api/me/data").exchange().expectStatus().isNoContent();

        assertThat(sessionRepository.findByUserIdOrderByCreatedAtAsc("gina")).isEmpty();
        assertThat(memoryRepository.findByUserIdOrderByCreatedAtDesc("gina")).isEmpty();
        assertThat(ticketRepository.findById(ticket)).get()
                .satisfies(t -> assertThat(t.getRequestedBy()).isEqualTo("gina"));
        assertThat(audit("action=USER_DATA_DELETED&actor=gina").items()).singleElement()
                .satisfies(e -> assertThat(e.details()).contains("1 conversation(s)", "1 memory(ies)", "tickets kept"));
    }

    @Test
    void retentionDeletesOnlyConversationsInactiveForTooLong() {
        RestTestClient hugo = as("hugo", "user");
        String old = newSession(hugo);
        String recent = newSession(hugo);
        givenModelReplies(answer("OK"));
        chat(hugo, old, "Old question").expectStatus().isOk();
        chat(hugo, recent, "New question").expectStatus().isOk();
        Timestamp longAgo = Timestamp.from(Instant.now().minusSeconds(40L * 24 * 3600));
        jdbcClient.sql("update agent_session set created_at = ? where id = ?").params(longAgo, old).update();
        jdbcClient.sql("update agent_run set created_at = ? where session_id = ?").params(longAgo, old).update();
        PrivacyService retention30Days = new PrivacyService(sessionRepository, runRepository, stepRepository,
                feedbackRepository, pendingTurnRepository, memoryRepository, ticketRepository, chatMemory, auditService, 30);

        Integer deleted = transactionTemplate.execute(status -> retention30Days.applyRetention());

        assertThat(deleted).isEqualTo(1);
        assertThat(sessionRepository.findById(old)).isEmpty();
        assertThat(sessionRepository.findById(recent)).isPresent();
        assertThat(audit("action=RETENTION_CLEANUP&actor=system").items()).isNotEmpty();
    }

    private AuditEventResponse.PageResponse audit(String query) {
        return admin.get().uri("/api/audit?" + query).exchange()
                .expectStatus().isOk()
                .expectBody(AuditEventResponse.PageResponse.class).returnResult().getResponseBody();
    }

    private Map<String, Object> settingsAsAdmin() {
        return admin.get().uri("/api/settings").exchange()
                .expectStatus().isOk()
                .expectBody(new ParameterizedTypeReference<Map<String, Object>>() { }).returnResult().getResponseBody();
    }

    private String newSession(RestTestClient user) {
        SessionResponse session = user.post().uri("/api/sessions").exchange()
                .expectStatus().isCreated()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        return session.id();
    }

    private RestTestClient.ResponseSpec chat(RestTestClient user, String session, String message) {
        return user.post().uri("/api/sessions/{id}/messages", session).body(new ChatRequest(message)).exchange();
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }

    private void givenModelReplies(ChatResponse first, ChatResponse... rest) {
        when(chatModel.call(any(Prompt.class))).thenReturn(first, rest);
    }

    private static ChatResponse answer(String text) {
        return response(new AssistantMessage(text));
    }

    private static ChatResponse toolCall(String tool, String arguments) {
        return response(AssistantMessage.builder()
                .content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("call-1", "function", tool, arguments)))
                .build());
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }
}
