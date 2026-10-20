package pl.piomin.services.deskpilot.agent;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.web.client.ResourceAccessException;
import pl.piomin.services.deskpilot.config.ModelCircuitBreakerProperties;
import pl.piomin.services.deskpilot.exception.ModelUnavailableException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ResilientChatModelTest {

    /** A model that fails while {@code down} is set, and counts how often it is really called. */
    static class FlakyModel implements ChatModel {
        final AtomicInteger calls = new AtomicInteger();
        volatile RuntimeException failure;

        @Override
        public ChatResponse call(Prompt prompt) {
            calls.incrementAndGet();
            if (failure != null) {
                throw failure;
            }
            return new ChatResponse(List.of(new Generation(new AssistantMessage("ok"))));
        }
    }

    private final FlakyModel model = new FlakyModel();
    private final ResilientChatModel resilient =
            new ResilientChatModel(model, new ModelCircuitBreakerProperties(true, 2, Duration.ofMillis(300)));

    @Test
    void opensAfterConsecutiveInfrastructureFailuresThenFailsFastAndRecovers() throws InterruptedException {
        model.failure = new ResourceAccessException("Connection refused");

        assertThatThrownBy(() -> resilient.call(new Prompt("a"))).isInstanceOf(ResourceAccessException.class);
        assertThatThrownBy(() -> resilient.call(new Prompt("b"))).isInstanceOf(ResourceAccessException.class);
        assertThat(resilient.state()).isEqualTo(CircuitBreaker.State.OPEN);

        // Open: refused at once, the model is not called.
        assertThatThrownBy(() -> resilient.call(new Prompt("c")))
                .isInstanceOf(ModelUnavailableException.class)
                .hasMessageContaining("Try again in 1 s");
        assertThat(model.calls).hasValue(2);

        // After the open duration one trial call goes through; it succeeds, so the breaker closes.
        Thread.sleep(400);
        model.failure = null;
        assertThat(resilient.call(new Prompt("d")).getResult().getOutput().getText()).isEqualTo("ok");
        assertThat(resilient.state()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    void requestErrorsDoNotOpenTheBreaker() {
        model.failure = new IllegalArgumentException("model 'x' not found");

        for (int i = 0; i < 4; i++) {
            assertThatThrownBy(() -> resilient.call(new Prompt("q"))).isInstanceOf(IllegalArgumentException.class);
        }
        assertThat(resilient.state()).isEqualTo(CircuitBreaker.State.CLOSED);
        assertThat(model.calls).hasValue(4);
    }

    @Test
    void canBeSwitchedOff() {
        ResilientChatModel disabled = new ResilientChatModel(model, new ModelCircuitBreakerProperties(false, 1, Duration.ofMinutes(1)));
        model.failure = new ResourceAccessException("Connection refused");

        for (int i = 0; i < 3; i++) {
            assertThatThrownBy(() -> disabled.call(new Prompt("q"))).isInstanceOf(ResourceAccessException.class);
        }
        assertThat(model.calls).hasValue(3);
    }

    @Test
    void recognisesInfrastructureFailuresInTheCauseChain() {
        assertThat(ResilientChatModel.isInfrastructureFailure(
                new RuntimeException("wrapped", new java.net.ConnectException("refused")))).isTrue();
        assertThat(ResilientChatModel.isInfrastructureFailure(new IllegalStateException("bad"))).isFalse();
    }
}
