package pl.piomin.services.deskpilot.config;

import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Per-user limits on the requests that make the model work ({@code agent.rate-limit.*}): chat messages and approval
 * decisions. Admins are not limited. A limit of 0 turns it off.
 *
 * @param perMinute requests per user per minute
 * @param perDay    requests per user per day
 */
@Validated
@ConfigurationProperties("agent.rate-limit")
public record RateLimitProperties(
        @DefaultValue("10") @Min(0) int perMinute,
        @DefaultValue("100") @Min(0) int perDay) {

    public boolean enabled() {
        return perMinute > 0 || perDay > 0;
    }
}
