package pl.piomin.services.deskpilot.agent;

import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentSession;
import pl.piomin.services.deskpilot.repository.AgentSessionRepository;

/**
 * Conversations, stored in the database so they survive a restart. Only one request per session runs at a time:
 * the session row is claimed in its own short transaction, never held open while the agent waits on the model.
 */
@Service
@Transactional(readOnly = true)
public class SessionService {

    /** A claim older than this is considered left behind by a crashed request (a run makes at most 20 model calls). */
    static final Duration STALE_CLAIM = Duration.ofMinutes(30);

    private final AgentSessionRepository repository;

    public SessionService(AgentSessionRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public AgentSession create(String userId) {
        return repository.save(new AgentSession(userId));
    }

    public AgentSession get(String sessionId) {
        return repository.findById(sessionId)
                .orElseThrow(() -> new NotFoundException("Session " + sessionId + " not found"));
    }

    /** Runs the action while holding the session; fails fast instead of queueing a second request. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public <T> T exclusively(String sessionId, Supplier<T> action) {
        Instant now = Instant.now();
        if (repository.claim(sessionId, now, now.minus(STALE_CLAIM)) == 0) {
            get(sessionId);
            throw new ConflictException("Another request is already running in session " + sessionId);
        }
        try {
            return action.get();
        } finally {
            repository.release(sessionId);
        }
    }
}
