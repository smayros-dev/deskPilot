package pl.piomin.services.deskpilot.notifications;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * E-mail and webhook notifications, bound from {@code agent.notifications.*}.
 *
 * @param enabled     send nothing when false
 * @param from        sender address of the e-mails
 * @param reviewers   addresses that get "approval needed" e-mails (Keycloak users are not known to the backend)
 * @param admins      addresses that get the escalation e-mail when an approval waits longer than the SLA
 * @param approvalSla how long a run may wait for approval before the admins are told
 * @param webhookUrl  optional incoming webhook (Slack, Teams...) that gets {@code {"text": "..."}} for the same events
 * @param baseUrl     address of the web app, for the links in the messages
 */
@ConfigurationProperties("agent.notifications")
public record NotificationProperties(
        @DefaultValue("false") boolean enabled,
        @DefaultValue("DeskPilot <deskpilot@example.com>") String from,
        @DefaultValue({"bob@example.com", "admin@example.com"}) List<String> reviewers,
        @DefaultValue("admin@example.com") List<String> admins,
        @DefaultValue("PT30M") Duration approvalSla,
        @DefaultValue("") String webhookUrl,
        @DefaultValue("http://localhost:4200") String baseUrl) {
}
