package pl.piomin.services.deskpilot.agent;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.model.RunStatus;

/**
 * Micrometer meters for the agent (visible under {@code /actuator/metrics/agent.*}). They complement
 * Spring AI's built-in {@code gen_ai.client.*} observations with agent-level signals.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    void runFinished(RunStatus status, long activeMillis) {
        Counter.builder("agent.runs").description("Finished agent runs").tag("status", status.name())
                .register(registry).increment();
        Timer.builder("agent.run.duration").description("Active time per run, excluding approval waits")
                .tag("status", status.name()).register(registry).record(Duration.ofMillis(activeMillis));
    }

    void modelCall(long durationMillis, long promptTokens, long completionTokens) {
        Timer.builder("agent.llm.latency").description("Latency of one model call").register(registry)
                .record(Duration.ofMillis(durationMillis));
        Counter.builder("agent.tokens").tag("type", "prompt").register(registry).increment(promptTokens);
        Counter.builder("agent.tokens").tag("type", "completion").register(registry).increment(completionTokens);
    }

    void toolCall(String tool, boolean error) {
        Counter.builder("agent.tool.calls").tag("tool", tool).tag("outcome", error ? "error" : "success")
                .register(registry).increment();
    }

    void approval(String decision) {
        Counter.builder("agent.approvals").tag("decision", decision).register(registry).increment();
    }

    void handOff(String specialist, String method) {
        Counter.builder("agent.handoffs").description("Messages routed to a specialist agent")
                .tag("specialist", specialist).tag("method", method).register(registry).increment();
    }

    void guardrailBlocked(String rule) {
        Counter.builder("agent.guardrail.blocked").tag("rule", rule).register(registry).increment();
    }

    public double guardrailBlockedTotal() {
        return registry.find("agent.guardrail.blocked").counters().stream().mapToDouble(Counter::count).sum();
    }
}
