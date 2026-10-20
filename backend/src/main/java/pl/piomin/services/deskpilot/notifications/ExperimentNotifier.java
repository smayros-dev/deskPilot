package pl.piomin.services.deskpilot.notifications;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import pl.piomin.services.deskpilot.events.ExperimentAutoStopped;

/** Tells the admins when DeskPilot stopped an A/B experiment itself (12m), after the change is committed. */
@Component
class ExperimentNotifier {

    private final NotificationProperties properties;
    private final Messenger messenger;

    ExperimentNotifier(NotificationProperties properties, Messenger messenger) {
        this.properties = properties;
        this.messenger = messenger;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onExperimentAutoStopped(ExperimentAutoStopped event) {
        if (!properties.enabled() || properties.admins().isEmpty()) {
            return;
        }
        messenger.send(properties.admins(),
                "[DeskPilot] Experiment stopped: " + event.name(),
                """
                The A/B experiment "%s" was stopped automatically; new conversations use the normal settings again.

                Reason: %s

                Results: %s/experiments
                """.formatted(event.name(), event.reason(), properties.baseUrl()));
    }
}
