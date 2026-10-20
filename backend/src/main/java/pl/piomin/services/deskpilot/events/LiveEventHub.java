package pl.piomin.services.deskpilot.events;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.piomin.services.deskpilot.security.CurrentUser;

/**
 * Open Server-Sent Events connections, per signed-in user ({@code GET /api/events}). Kept in memory, so events reach
 * the browsers connected to this backend instance only — enough for a single instance; several instances would need
 * a shared broker (e.g. Postgres LISTEN/NOTIFY or Redis pub/sub). A comment is sent every 25 s so proxies keep idle
 * connections open; the browser reconnects when a connection ends.
 */
@Component
public class LiveEventHub {

    private static final Logger LOG = LoggerFactory.getLogger(LiveEventHub.class);

    /** The browser reconnects after this; keeps abandoned connections from piling up. */
    static final Duration CONNECTION_TIMEOUT = Duration.ofMinutes(30);

    /** One browser tab of a user. */
    public record Subscriber(CurrentUser user, SseEmitter emitter) {
    }

    private final Set<Subscriber> subscribers = ConcurrentHashMap.newKeySet();

    public SseEmitter subscribe(CurrentUser user) {
        SseEmitter emitter = new SseEmitter(CONNECTION_TIMEOUT.toMillis());
        Subscriber subscriber = new Subscriber(user, emitter);
        emitter.onCompletion(() -> subscribers.remove(subscriber));
        emitter.onTimeout(() -> subscribers.remove(subscriber));
        emitter.onError(e -> subscribers.remove(subscriber));
        subscribers.add(subscriber);
        send(subscriber, "connected", Map.of("user", user.username()));
        return emitter;
    }

    /** Sends the event to every connection of the given user. */
    public void sendToUser(String username, String event, Object data) {
        sendTo(s -> s.user().username().equals(username), event, data);
    }

    /** Sends the event to every connection whose user matches. */
    public void sendTo(Predicate<Subscriber> recipients, String event, Object data) {
        subscribers.stream().filter(recipients).forEach(s -> send(s, event, data));
    }

    int connections() {
        return subscribers.size();
    }

    @Scheduled(fixedRateString = "${agent.events.heartbeat-millis:25000}", initialDelayString = "${agent.events.heartbeat-millis:25000}")
    void heartbeat() {
        subscribers.forEach(s -> {
            synchronized (s) {
                try {
                    s.emitter().send(SseEmitter.event().comment("keep-alive"));
                } catch (IOException | IllegalStateException e) {
                    drop(s);
                }
            }
        });
    }

    private void send(Subscriber subscriber, String event, Object data) {
        // One write at a time per connection: events and heartbeats come from different threads.
        synchronized (subscriber) {
            try {
                subscriber.emitter().send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
            } catch (IOException | IllegalStateException e) {
                LOG.debug("Dropping closed event connection of {}", subscriber.user().username());
                drop(subscriber);
            }
        }
    }

    private void drop(Subscriber subscriber) {
        subscribers.remove(subscriber);
        try {
            subscriber.emitter().complete();
        } catch (IllegalStateException ignored) {
            // already completed
        }
    }
}
