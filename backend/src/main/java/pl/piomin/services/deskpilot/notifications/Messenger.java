package pl.piomin.services.deskpilot.notifications;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Delivers a notification by e-mail and, when configured, to the webhook. Runs on a virtual thread and only logs
 * failures: a mail server or webhook that is down must never affect a run.
 */
@Component
class Messenger {

    private static final Logger LOG = LoggerFactory.getLogger(Messenger.class);

    private final NotificationProperties properties;
    private final ObjectProvider<JavaMailSender> mailSender;
    private final RestClient webhook;

    Messenger(NotificationProperties properties, ObjectProvider<JavaMailSender> mailSender) {
        this.properties = properties;
        this.mailSender = mailSender;
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(Duration.ofSeconds(5));
        requestFactory.setReadTimeout(Duration.ofSeconds(10));
        this.webhook = RestClient.builder().requestFactory(requestFactory).build();
    }

    void send(List<String> recipients, String subject, String body) {
        if (!properties.enabled()) {
            return;
        }
        Thread.ofVirtual().name("notification").start(() -> {
            sendMail(recipients, subject, body);
            sendWebhook(subject, body);
        });
    }

    private void sendMail(List<String> recipients, String subject, String body) {
        JavaMailSender sender = mailSender.getIfAvailable();
        if (sender == null || recipients.isEmpty()) {
            return;
        }
        try {
            SimpleMailMessage message = new SimpleMailMessage();
            message.setFrom(properties.from());
            message.setTo(recipients.toArray(String[]::new));
            message.setSubject(subject);
            message.setText(body);
            sender.send(message);
        } catch (RuntimeException e) {
            LOG.warn("Could not send the e-mail \"{}\" to {}: {}", subject, recipients, e.getMessage());
        }
    }

    private void sendWebhook(String subject, String body) {
        if (properties.webhookUrl().isBlank()) {
            return;
        }
        try {
            webhook.post().uri(properties.webhookUrl())
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of("text", subject + "\n" + body))
                    .retrieve()
                    .toBodilessEntity();
        } catch (RuntimeException e) {
            LOG.warn("Could not call the notification webhook: {}", e.getMessage());
        }
    }
}
