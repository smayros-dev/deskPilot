package pl.piomin.services.agentai.agent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.stereotype.Component;
import pl.piomin.services.agentai.exception.NotFoundException;

/**
 * In-memory session registry. Sessions (and runs paused for approval) are lost on restart;
 * persisting them is part of Step 11.
 */
@Component
public class SessionStore {

    private final Map<String, AgentSession> sessions = new ConcurrentHashMap<>();

    public AgentSession create(String userId) {
        AgentSession session = new AgentSession(userId);
        sessions.put(session.id(), session);
        return session;
    }

    public AgentSession get(String sessionId) {
        AgentSession session = sessions.get(sessionId);
        if (session == null) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        return session;
    }
}
