package pl.piomin.services.deskpilot.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * A/B experiments ({@code agent.experiments.*}, 12m).
 *
 * @param maxProviderFailures consecutive runs of one variant failing with a model-provider error after which the
 *                            experiment is stopped automatically
 * @param minSample           finished runs (or ratings) each variant needs before results are tested for significance
 */
@Validated
@ConfigurationProperties("agent.experiments")
public record ExperimentProperties(
        @DefaultValue("3") @Min(1) int maxProviderFailures,
        @DefaultValue("10") @Min(1) int minSample) {
}
