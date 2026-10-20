package pl.piomin.services.deskpilot.notifications;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.mail.javamail.JavaMailSender;
import pl.piomin.services.deskpilot.events.RunStatusChanged;
import pl.piomin.services.deskpilot.events.TicketCreated;
import pl.piomin.services.deskpilot.service.RunService;
import pl.piomin.services.deskpilot.service.TicketService;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

class NotificationUnitTest {

    @Test
    void disabledNotificationsSendNothingAndReadNothing() {
        Messenger messenger = mock(Messenger.class);
        RunService runService = mock(RunService.class);
        TicketService ticketService = mock(TicketService.class);
        NotificationService service = new NotificationService(properties(false, ""), messenger, runService, ticketService,
                mock(ContactService.class), mock(ApprovalEscalationRepository.class));

        service.onRunStatusChanged(new RunStatusChanged("run-1"));
        service.onTicketCreated(new TicketCreated(1));
        service.escalateOverdueApprovals();

        verifyNoInteractions(messenger, runService, ticketService);
    }

    @Test
    void webhookGetsTheMessageAsJsonText() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        AtomicReference<String> received = new AtomicReference<>();
        CountDownLatch latch = new CountDownLatch(1);
        server.createContext("/hook", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            exchange.sendResponseHeaders(200, -1);
            exchange.close();
            latch.countDown();
        });
        server.start();
        try {
            String url = "http://localhost:" + server.getAddress().getPort() + "/hook";
            @SuppressWarnings("unchecked")
            ObjectProvider<JavaMailSender> noMail = mock(ObjectProvider.class);
            new Messenger(properties(true, url), noMail).send(List.of(), "Approval needed", "Approve it: http://x/approvals");

            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
            assertThat(received.get()).isEqualTo("{\"text\":\"Approval needed\\nApprove it: http://x/approvals\"}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void longUserTextIsShortenedToOneLine() {
        String text = "line one\n\nline two " + "x".repeat(400);

        String shortened = NotificationService.shorten(text);

        assertThat(shortened).startsWith("line one line two").endsWith("…").doesNotContain("\n");
        assertThat(shortened).hasSize(NotificationService.MAX_TEXT + 1);
    }

    private static NotificationProperties properties(boolean enabled, String webhookUrl) {
        return new NotificationProperties(enabled, "DeskPilot <deskpilot@example.com>", List.of("bob@example.com"),
                List.of("admin@example.com"), Duration.ofMinutes(30), webhookUrl, "http://localhost:4200");
    }
}
