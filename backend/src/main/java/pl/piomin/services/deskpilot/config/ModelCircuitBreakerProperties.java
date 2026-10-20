package pl.piomin.services.deskpilot.config;

import java.time.Duration;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Circuit breaker around every model call ({@code agent.model-circuit-breaker.*}).
 *
 * @param enabled          when false, calls go straight to the model
 * @param failureThreshold consecutive infrastructure failures (connection refused, timeout, 5xx) that open the breaker
 * @param openDuration     how long calls fail fast before one trial call is let through
 */
@Validated
@ConfigurationProperties("agent.model-circuit-breaker")
public record ModelCircuitBreakerProperties(
        @DefaultValue("true") boolean enabled,
        @DefaultValue("5") @Min(1) int failureThreshold,
        @DefaultValue("30s") @NotNull Duration openDuration) {
}
