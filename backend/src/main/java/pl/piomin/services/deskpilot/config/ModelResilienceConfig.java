package pl.piomin.services.deskpilot.config;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.ollama.OllamaChatModel;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Primary;
import pl.piomin.services.deskpilot.agent.ResilientChatModel;

/**
 * Puts the circuit breaker in front of the auto-configured Ollama model. The bean is named {@code chatModel} and is
 * primary, so everything that injects a {@code ChatModel} gets the protected one (and tests that mock
 * {@code ChatModel chatModel} still replace it).
 */
@Configuration(proxyBeanMethods = false)
class ModelResilienceConfig {

    @Bean(name = "chatModel")
    @Primary
    ResilientChatModel chatModel(OllamaChatModel ollamaChatModel, ModelCircuitBreakerProperties properties,
                                 MeterRegistry registry) {
        ResilientChatModel model = new ResilientChatModel(ollamaChatModel, properties);
        // 0 = closed, 1 = open, 2 = half-open, 3 = other (disabled, forced open...)
        Gauge.builder("agent.model.circuit.state", model, m -> switch (m.state()) {
                    case CLOSED -> 0;
                    case OPEN -> 1;
                    case HALF_OPEN -> 2;
                    default -> 3;
                })
                .description("State of the circuit breaker around the model")
                .register(registry);
        return model;
    }

    /** Shows in /actuator/health when model calls are being refused because the breaker is open. */
    @Bean("modelCircuitBreaker")
    HealthIndicator modelCircuitBreakerHealth(ChatModel chatModel) {
        return () -> {
            if (!(chatModel instanceof ResilientChatModel resilient)) {
                return Health.unknown().withDetail("state", "not configured").build();
            }
            return switch (resilient.state()) {
                case OPEN -> Health.down().withDetail("state", "OPEN")
                        .withDetail("hint", "Several model calls failed in a row; calls are refused for a short while.").build();
                default -> Health.up().withDetail("state", resilient.state().name()).build();
            };
        };
    }
}
