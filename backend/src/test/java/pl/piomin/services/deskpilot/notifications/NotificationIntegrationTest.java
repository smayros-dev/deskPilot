package pl.piomin.services.deskpilot.notifications;

import java.util.Arrays;
import java.util.List;
import java.util.function.Predicate;

import com.icegreen.greenmail.junit5.GreenMailExtension;
import com.icegreen.greenmail.util.GreenMailUtil;
import com.icegreen.greenmail.util.ServerSetupTest;
import jakarta.mail.Message;
import jakarta.mail.internet.MimeMessage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.RegisterExtension;
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
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.RunService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

/**
 * Notifications end to end over HTTP, with a real SMTP server (GreenMail): reviewers, requester, SLA escalation.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "agent.notifications.enabled=true",
        "agent.notifications.approval-sla=PT0S",
        "agent.notifications.sla-check-interval=PT1H",
        // IPv4 address: GreenMail listens on 127.0.0.1, and on Windows "localhost" tries ::1 first and times out.
        "spring.mail.host=127.0.0.1",
        "spring.mail.port=3025"})
@Import({TestcontainersConfiguration.class, TestSecurityConfiguration.class})
@AutoConfigureRestTestClient
@ActiveProfiles("test")
class NotificationIntegrationTest {

    @RegisterExtension
    static final GreenMailExtension MAIL = new GreenMailExtension(ServerSetupTest.SMTP);

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
    private NotificationService notifications;

    @Autowired
    private RunService runService;

    private RestTestClient alice;
    private RestTestClient bob;

    @BeforeEach
    void setUp() {
        alice = as("alice", "user");
        bob = as("bob", "user", "reviewer");
    }

    @Test
    void reviewersHearAboutTheApprovalAndTheRequesterAboutTheTicket() throws Exception {
        RunResponse paused = askForTicket("Please open a ticket: my docking station is broken");

        // One message to both reviewers (GreenMail keeps a copy per mailbox).
        List<MimeMessage> approvals = awaitMessages(m -> subject(m).startsWith("[DeskPilot] Approval needed"), 2);
        assertThat(recipientsOf(approvals.toArray(MimeMessage[]::new)))
                .containsExactlyInAnyOrder("bob@example.com", "admin@example.com");
        MimeMessage approval = approvals.getFirst();
        assertThat(approval.getSubject()).isEqualTo("[DeskPilot] Approval needed: create_ticket for alice");
        assertThat(GreenMailUtil.getBody(approval)).contains("docking station", "http://localhost:4200/approvals",
                "/runs/" + paused.id());

        MAIL.purgeEmailFromAllMailboxes();
        givenModelReplies(answer("Your ticket was created."));
        bob.post().uri("/api/runs/{id}/approval", paused.id()).body(new ApprovalRequest(true, null))
                .exchange().expectStatus().isOk();

        MimeMessage ticket = awaitMessages(m -> subject(m).startsWith("[DeskPilot] Ticket #"), 1).getFirst();
        assertThat(recipientsOf(ticket)).containsExactly("alice@example.com");
        assertThat(ticket.getSubject()).startsWith("[DeskPilot] Ticket #").endsWith("Docking station");
    }

    @Test
    void overdueApprovalIsEscalatedToTheAdminsOnce() throws Exception {
        RunResponse paused = askForTicket("Please open a ticket: I need a second monitor");
        awaitMessages(m -> GreenMailUtil.getBody(m).contains("/runs/" + paused.id()), 2);
        MAIL.purgeEmailFromAllMailboxes();

        notifications.escalateOverdueApprovals();
        // Runs paused by other tests of this class may be escalated in the same pass: look at this run's mail only.
        List<MimeMessage> overdue = awaitMessages(m -> subject(m).startsWith("[DeskPilot] Approval overdue")
                && GreenMailUtil.getBody(m).contains("/runs/" + paused.id()), 1);
        assertThat(overdue).singleElement().satisfies(m -> {
            assertThat(m.getSubject()).startsWith("[DeskPilot] Approval overdue: create_ticket for alice");
            assertThat(recipientsOf(m)).containsExactly("admin@example.com");
        });

        MAIL.purgeEmailFromAllMailboxes();
        notifications.escalateOverdueApprovals();
        Thread.sleep(1_000);
        assertThat(MAIL.getReceivedMessages()).noneMatch(m -> GreenMailUtil.getBody(m).contains("/runs/" + paused.id()));
    }

    @Test
    void evaluationRunsNotifyNobody() throws Exception {
        AgentRun run = runService.start("evaluation-session", "evaluation", "Open a ticket", "qwen3:8b", RunSource.EVALUATION);
        runService.pauseForApproval(run.getId(), 0);

        Thread.sleep(1_000);
        assertThat(runService.get(run.getId()).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        assertThat(MAIL.getReceivedMessages()).isEmpty();
    }

    /** Waits until at least {@code count} received messages match (polling: GreenMail's counter ignores purges). */
    private static List<MimeMessage> awaitMessages(Predicate<MimeMessage> filter, int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 15_000;
        while (true) {
            List<MimeMessage> matching = Arrays.stream(MAIL.getReceivedMessages()).filter(filter).toList();
            if (matching.size() >= count || System.currentTimeMillis() > deadline) {
                assertThat(matching).as("received messages").hasSizeGreaterThanOrEqualTo(count);
                return matching;
            }
            Thread.sleep(100);
        }
    }

    private static String subject(MimeMessage message) {
        try {
            return message.getSubject();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private RunResponse askForTicket(String message) {
        SessionResponse session = alice.post().uri("/api/sessions").exchange()
                .expectStatus().isCreated()
                .expectBody(SessionResponse.class).returnResult().getResponseBody();
        assertThat(session).isNotNull();
        givenModelReplies(response(AssistantMessage.builder().content("")
                .toolCalls(List.of(new AssistantMessage.ToolCall("c1", "function", "create_ticket",
                        "{\"title\":\"Docking station\",\"description\":\"Broken\",\"priority\":\"LOW\"}")))
                .build()));
        RunResponse paused = alice.post().uri("/api/sessions/{id}/messages", session.id())
                .body(new ChatRequest(message)).exchange()
                .expectStatus().isOk()
                .expectBody(RunResponse.class).returnResult().getResponseBody();
        assertThat(paused).isNotNull();
        assertThat(paused.status()).isEqualTo(RunStatus.AWAITING_APPROVAL);
        return paused;
    }

    private RestTestClient as(String username, String... roles) {
        return client.mutate()
                .defaultHeader(HttpHeaders.AUTHORIZATION, "Bearer " + tokens.token(username, roles))
                .build();
    }

    private static List<String> recipientsOf(MimeMessage... messages) {
        return Arrays.stream(messages).flatMap(m -> {
            try {
                return Arrays.stream(m.getRecipients(Message.RecipientType.TO)).map(Object::toString);
            } catch (Exception e) {
                throw new IllegalStateException(e);
            }
        }).distinct().toList();
    }

    private void givenModelReplies(ChatResponse first, ChatResponse... rest) {
        when(chatModel.call(any(Prompt.class))).thenReturn(first, rest);
    }

    private static ChatResponse answer(String text) {
        return response(new AssistantMessage(text));
    }

    private static ChatResponse response(AssistantMessage message) {
        return new ChatResponse(List.of(new Generation(message)),
                ChatResponseMetadata.builder().usage(new DefaultUsage(100, 20)).build());
    }
}
