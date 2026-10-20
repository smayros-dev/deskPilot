package pl.piomin.services.deskpilot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Supervisor + specialist agents ({@code agent.specialists.*}, 12f). Turned on and off at runtime in Settings; these
 * values tune how a message is routed.
 *
 * @param enabled        default of the Settings toggle (first start and "Reset to defaults")
 * @param minSimilarity  cosine similarity to a specialist's examples needed to route by meaning
 * @param minMargin      how far the best specialist must be ahead of the second one to route by meaning
 * @param modelFallback  when keywords and meaning are both inconclusive, ask the chat model to choose (one extra call)
 */
@Validated
@ConfigurationProperties("agent.specialists")
public record SpecialistProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("0.7") @DecimalMin("0.0") @DecimalMax("1.0") double minSimilarity,
        @DefaultValue("0.03") @DecimalMin("0.0") @DecimalMax("1.0") double minMargin,
        @DefaultValue("false") boolean modelFallback) {
}
