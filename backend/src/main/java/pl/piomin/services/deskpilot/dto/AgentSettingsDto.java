package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.Set;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pl.piomin.services.deskpilot.model.AgentSettings;

/**
 * Agent settings as edited in the UI. Used for both GET (with {@code updatedAt}) and PUT (ignored).
 *
 * @param contextWindow   tokens the model can see at once (Ollama {@code num_ctx}); higher costs RAM and speed
 * @param maxOutputTokens upper bound on tokens generated per model call
 * @param thinking        reasoning mode for models that support it (much slower on CPU)
 * @param maxIterations   model calls allowed per run before escalating to a human
 * @param groundingCheck  when the model answers without any tool call, ask it once to search the knowledge base
 * @param hybridSearch    combine keyword (full-text) and semantic ranking in knowledge-base searches
 */
public record AgentSettingsDto(
        @NotBlank @Size(max = 100) String chatModel,
        @DecimalMin("0.0") @DecimalMax("2.0") double temperature,
        @DecimalMin("0.05") @DecimalMax("1.0") double topP,
        @Min(1024) @Max(131072) int contextWindow,
        @Min(64) @Max(32768) int maxOutputTokens,
        boolean thinking,
        @Min(1) @Max(20) int maxIterations,
        @Min(1) @Max(10) int ragTopK,
        @DecimalMin("0.0") @DecimalMax("1.0") double ragSimilarityThreshold,
        boolean groundingCheck,
        boolean hybridSearch,
        @NotNull Set<@NotBlank String> disabledTools,
        @NotNull Set<@NotBlank String> approvalTools,
        @Size(max = 8000) String systemPrompt,
        Instant updatedAt) {

    public static AgentSettingsDto of(AgentSettings settings) {
        AgentSettings.Values v = settings.values();
        return new AgentSettingsDto(v.chatModel(), v.temperature(), v.topP(), v.contextWindow(), v.maxOutputTokens(),
                v.thinking(), v.maxIterations(), v.ragTopK(), v.ragSimilarityThreshold(), v.groundingCheck(),
                v.hybridSearch(), v.disabledTools(), v.approvalTools(), v.systemPrompt(), settings.getUpdatedAt());
    }

    public AgentSettings.Values toValues() {
        return new AgentSettings.Values(chatModel.strip(), temperature, topP, contextWindow, maxOutputTokens, thinking,
                maxIterations, ragTopK, ragSimilarityThreshold, groundingCheck, hybridSearch, disabledTools, approvalTools, systemPrompt);
    }
}
