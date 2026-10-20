package pl.piomin.services.deskpilot.agent;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import pl.piomin.services.deskpilot.model.FeedbackRating;

import static org.assertj.core.api.Assertions.assertThat;

class AgentMetricsTest {

    @Test
    void providerComesFromTheCloudPrefixOtherwiseTheRunIsLocal() {
        assertThat(AgentMetrics.provider("qwen3:8b")).isEqualTo("ollama");
        assertThat(AgentMetrics.provider("gemma4:26b")).isEqualTo("ollama");
        assertThat(AgentMetrics.provider("nomic-embed-text")).isEqualTo("ollama");
        assertThat(AgentMetrics.provider("mistral:mistral-small-latest")).isEqualTo("mistral");
        assertThat(AgentMetrics.provider("gemini:gemini-2.5-flash")).isEqualTo("gemini");
    }

    @Test
    void feedbackIsCountedPerRatingAndModel() {
        SimpleMeterRegistry registry = new SimpleMeterRegistry();
        AgentMetrics metrics = new AgentMetrics(registry);

        metrics.feedback(FeedbackRating.UP, "qwen3:8b");
        metrics.feedback(FeedbackRating.UP, "qwen3:8b");
        metrics.feedback(FeedbackRating.DOWN, null);

        assertThat(registry.get("agent.feedback").tag("rating", "UP").tag("model", "qwen3:8b").counter().count())
                .isEqualTo(2);
        assertThat(registry.get("agent.feedback").tag("rating", "DOWN").tag("model", "unknown").counter().count())
                .isEqualTo(1);
    }
}
