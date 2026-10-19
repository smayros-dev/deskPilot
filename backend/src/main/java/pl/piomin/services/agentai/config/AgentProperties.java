package pl.piomin.services.agentai.config;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Agent behaviour settings, bound from the {@code agent.*} properties.
 *
 * @param maxIterations upper bound on model calls in a single run; when reached the run is escalated to a human
 * @param rag           retrieval settings for the knowledge-base search tool
 * @param guardrails    input/output safety limits
 */
@Validated
@ConfigurationProperties("agent")
public record AgentProperties(
        @Min(1) @Max(20) int maxIterations,
        @NotNull @Valid Rag rag,
        @NotNull @Valid Guardrails guardrails) {

    /**
     * @param similarityThreshold minimum cosine similarity for a knowledge-base hit to count as relevant
     * @param topK                maximum number of articles returned per search
     * @param indexOnStartup      embed the knowledge base into the vector store when the app starts
     *                            (needs Ollama; disabled in tests)
     */
    public record Rag(
            @DecimalMin("0.0") @DecimalMax("1.0") double similarityThreshold,
            @Min(1) @Max(10) int topK,
            boolean indexOnStartup) {
    }

    /**
     * @param maxInputChars      longest user message accepted by the agent
     * @param maxToolOutputChars tool results longer than this are truncated before reaching the model
     * @param blockedPatterns    case-insensitive regular expressions that reject a user message (prompt-injection markers)
     */
    public record Guardrails(
            @Min(100) @Max(100_000) int maxInputChars,
            @Min(500) @Max(100_000) int maxToolOutputChars,
            @NotNull List<String> blockedPatterns) {
    }
}
