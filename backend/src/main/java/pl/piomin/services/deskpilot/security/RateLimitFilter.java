package pl.piomin.services.deskpilot.security;

import java.io.IOException;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import io.github.bucket4j.Bandwidth;
import io.github.bucket4j.Bucket;
import io.github.bucket4j.local.LocalBucketBuilder;
import io.github.bucket4j.ConsumptionProbe;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import pl.piomin.services.deskpilot.config.RateLimitProperties;
import tools.jackson.databind.json.JsonMapper;

/**
 * Per-user token buckets (12n) on the requests that make the model work: sending a chat message and deciding on an
 * approval. Runs after Spring Security, so the user comes from the token. Over the limit the request is refused with
 * 429, a Problem Detail and {@code Retry-After}. Admins are not limited.
 * <p>
 * Buckets live in memory (bounded, evicted after a day of inactivity): with several backend instances each instance
 * counts on its own, so the effective limit is multiplied; a shared store (e.g. Bucket4j on Postgres) would fix that.
 */
@Component
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Pattern LIMITED = Pattern.compile(
            "/api/sessions/[^/]+/messages(/stream)?|/api/runs/[^/]+/approval(/stream)?");

    private final RateLimitProperties properties;
    private final JsonMapper jsonMapper;
    private final Cache<String, Bucket> buckets = Caffeine.newBuilder()
            .maximumSize(10_000)
            .expireAfterAccess(Duration.ofDays(1))
            .build();

    public RateLimitFilter(RateLimitProperties properties, JsonMapper jsonMapper) {
        this.properties = properties;
        this.jsonMapper = jsonMapper;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !properties.enabled() || !"POST".equals(request.getMethod())
                || !LIMITED.matcher(request.getRequestURI()).matches();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || CurrentUser.of(authentication).isAdmin()) {
            chain.doFilter(request, response);
            return;
        }
        ConsumptionProbe probe = buckets.get(authentication.getName(), user -> newBucket()).tryConsumeAndReturnRemaining(1);
        if (probe.isConsumed()) {
            response.setHeader("X-RateLimit-Remaining", Long.toString(probe.getRemainingTokens()));
            chain.doFilter(request, response);
            return;
        }
        long retryAfter = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(probe.getNanosToWaitForRefill()) + 1);
        Map<String, Object> problem = new LinkedHashMap<>();
        problem.put("type", "about:blank");
        problem.put("title", "Too many requests");
        problem.put("status", HttpStatus.TOO_MANY_REQUESTS.value());
        problem.put("detail", "You are sending messages too fast. Try again in " + retryAfter + " s.");
        problem.put("instance", request.getRequestURI());
        problem.put("retryAfterSeconds", retryAfter);
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        response.setHeader(HttpHeaders.RETRY_AFTER, Long.toString(retryAfter));
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.getWriter().write(jsonMapper.writeValueAsString(problem));
    }

    private Bucket newBucket() {
        LocalBucketBuilder builder = Bucket.builder();
        if (properties.perMinute() > 0) {
            builder.addLimit(Bandwidth.builder().capacity(properties.perMinute())
                    .refillGreedy(properties.perMinute(), Duration.ofMinutes(1)).build());
        }
        if (properties.perDay() > 0) {
            builder.addLimit(Bandwidth.builder().capacity(properties.perDay())
                    .refillIntervally(properties.perDay(), Duration.ofDays(1)).build());
        }
        return builder.build();
    }
}
