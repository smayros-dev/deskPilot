package pl.piomin.services.agentai.agent;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import pl.piomin.services.agentai.exception.ConflictException;

/**
 * A conversation between one user and the agent. Only one request per session runs at a time.
 */
public final class AgentSession {

    private final String id;
    private final String userId;
    private final Instant createdAt;
    private final ReentrantLock lock = new ReentrantLock();
    private ActiveTurn activeTurn;

    AgentSession(String userId) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    /** Runs the action while holding the session; fails fast instead of queueing a second request. */
    <T> T exclusively(Supplier<T> action) {
        if (!lock.tryLock()) {
            throw new ConflictException("Another request is already running in session " + id);
        }
        try {
            return action.get();
        } finally {
            lock.unlock();
        }
    }

    Optional<ActiveTurn> activeTurn() {
        return Optional.ofNullable(activeTurn);
    }

    void begin(ActiveTurn turn) {
        this.activeTurn = turn;
    }

    void end() {
        this.activeTurn = null;
    }

    public String id() {
        return id;
    }

    public String userId() {
        return userId;
    }

    public Instant createdAt() {
        return createdAt;
    }
}
