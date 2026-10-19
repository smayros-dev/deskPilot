package pl.piomin.services.agentai.agent.tool;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

import org.springframework.ai.chat.model.ToolContext;
import pl.piomin.services.agentai.agent.AgentConfig;

/**
 * Per-run data handed to tools through Spring AI's {@link ToolContext}. It is never shown to the
 * model, so tools act on behalf of the real user rather than whatever user id the model might invent.
 */
public final class RunContext {

    static final String KEY = "agent.run";

    private final String userId;
    private final String sessionId;
    private final String runId;
    private final AgentConfig config;
    private final AtomicReference<String> escalationReason = new AtomicReference<>();

    public RunContext(String userId, String sessionId, String runId, AgentConfig config) {
        this.userId = userId;
        this.sessionId = sessionId;
        this.runId = runId;
        this.config = config;
    }

    public static RunContext from(ToolContext toolContext) {
        Object value = toolContext == null ? null : toolContext.getContext().get(KEY);
        if (value instanceof RunContext context) {
            return context;
        }
        throw new IllegalStateException("Tool called without a run context");
    }

    public ToolContext toToolContext() {
        return new ToolContext(Map.of(KEY, this));
    }

    public void requestEscalation(String reason) {
        escalationReason.set(reason);
    }

    public Optional<String> escalationReason() {
        return Optional.ofNullable(escalationReason.get());
    }

    public String userId() {
        return userId;
    }

    public String sessionId() {
        return sessionId;
    }

    public String runId() {
        return runId;
    }

    /** Settings snapshot of this run (model, RAG parameters, enabled tools...). */
    public AgentConfig config() {
        return config;
    }
}
