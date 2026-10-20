package pl.piomin.services.deskpilot.agent;

import java.time.Duration;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunStatus;

/**
 * Micrometer meters for the agent (visible under {@code /actuator/metrics/agent.*} and exported over OTLP to Grafana,
 * 12p). They complement Spring AI's built-in {@code gen_ai.client.*} observations with agent-level signals. Durations
 * publish histogram buckets so Grafana can compute p95 latency across instances.
 */
@Component
public class AgentMetrics {

    private final MeterRegistry registry;

    public AgentMetrics(MeterRegistry registry) {
        this.registry = registry;
    }

    /** A run reached a final or paused state; tagged with its model ("qwen3:8b", "mistral:mistral-small-latest"). */
    void runFinished(AgentRun run) {
        RunStatus status = run.getStatus();
        String model = run.getModel() == null ? "unknown" : run.getModel();
        String provider = provider(model);
        Counter.builder("agent.runs").description("Finished agent runs").tag("status", status.name())
                .tag("model", model).tag("provider", provider).register(registry).increment();
        Timer.builder("agent.run.duration").description("Active time per run, excluding approval waits")
                .tag("status", status.name()).tag("model", model).tag("provider", provider)
                .publishPercentileHistogram().register(registry).record(Duration.ofMillis(run.getActiveMillis()));
    }

    void modelCall(long durationMillis, long promptTokens, long completionTokens) {
        Timer.builder("agent.llm.latency").description("Latency of one model call").publishPercentileHistogram()
                .register(registry).record(Duration.ofMillis(durationMillis));
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

    public void feedback(FeedbackRating rating, String model) {
        Counter.builder("agent.feedback").description("Ratings given to answers").tag("rating", rating.name())
                .tag("model", model == null ? "unknown" : model).register(registry).increment();
    }

    void guardrailBlocked(String rule) {
        Counter.builder("agent.guardrail.blocked").tag("rule", rule).register(registry).increment();
    }

    public double guardrailBlockedTotal() {
        return registry.find("agent.guardrail.blocked").counters().stream().mapToDouble(Counter::count).sum();
    }

    /** Cloud runs are labelled "provider:model"; anything else (including Ollama's "qwen3:8b") is local. */
    static String provider(String model) {
        int colon = model.indexOf(':');
        String prefix = colon < 0 ? "" : model.substring(0, colon);
        return CloudProviderService.DEFINITIONS.stream().anyMatch(d -> d.id().equals(prefix)) ? prefix : "ollama";
    }
}
