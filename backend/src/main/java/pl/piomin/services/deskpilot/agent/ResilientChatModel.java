package pl.piomin.services.deskpilot.agent;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.retry.TransientAiException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import pl.piomin.services.deskpilot.config.ModelCircuitBreakerProperties;
import pl.piomin.services.deskpilot.exception.ModelUnavailableException;
import reactor.core.publisher.Flux;

/**
 * Wraps the model with a circuit breaker (12n): after {@code failureThreshold} infrastructure failures in a row the
 * model is not called for {@code openDuration}, and requests fail at once with {@link ModelUnavailableException}
 * (503) instead of each waiting for connection timeouts and retries. One trial call then decides whether to close
 * it again. Only infrastructure errors count; a bad request (e.g. a missing model) does not open the breaker.
 * Every model caller (agent loop, self-check, vision, evaluation) goes through this bean.
 */
public class ResilientChatModel implements ChatModel {

    private static final Logger LOG = LoggerFactory.getLogger(ResilientChatModel.class);

    private final ChatModel delegate;
    private final CircuitBreaker breaker;
    private final Duration openDuration;

    public ResilientChatModel(ChatModel delegate, ModelCircuitBreakerProperties properties) {
        this.delegate = delegate;
        this.openDuration = properties.openDuration();
        CircuitBreakerConfig config = CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(properties.failureThreshold())
                .minimumNumberOfCalls(properties.failureThreshold())
                .failureRateThreshold(100)
                .waitDurationInOpenState(properties.openDuration())
                .permittedNumberOfCallsInHalfOpenState(1)
                .recordException(ResilientChatModel::isInfrastructureFailure)
                .build();
        this.breaker = CircuitBreaker.of("model", config);
        breaker.getEventPublisher().onStateTransition(event -> LOG.warn("Model circuit breaker: {}", event.getStateTransition()));
        if (!properties.enabled()) {
            breaker.transitionToDisabledState();
        }
    }

    @Override
    public ChatResponse call(Prompt prompt) {
        try {
            return breaker.executeSupplier(() -> delegate.call(prompt));
        } catch (CallNotPermittedException e) {
            throw new ModelUnavailableException(Math.max(1, openDuration.toSeconds()));
        }
    }

    @Override
    public Flux<ChatResponse> stream(Prompt prompt) {
        if (!breaker.tryAcquirePermission()) {
            return Flux.error(new ModelUnavailableException(Math.max(1, openDuration.toSeconds())));
        }
        long start = System.nanoTime();
        return delegate.stream(prompt)
                .doOnComplete(() -> breaker.onSuccess(System.nanoTime() - start, TimeUnit.NANOSECONDS))
                .doOnError(e -> breaker.onError(System.nanoTime() - start, TimeUnit.NANOSECONDS, e));
    }

    @Override
    public ChatOptions getDefaultOptions() {
        return delegate.getDefaultOptions();
    }

    public CircuitBreaker.State state() {
        return breaker.getState();
    }

    /** Errors that say the model server is down or overloaded, as opposed to a problem with the request. */
    static boolean isInfrastructureFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof ResourceAccessException || t instanceof HttpServerErrorException
                    || t instanceof TransientAiException || t instanceof IOException) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
