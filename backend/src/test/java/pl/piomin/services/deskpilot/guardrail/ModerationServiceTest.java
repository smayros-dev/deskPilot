package pl.piomin.services.deskpilot.guardrail;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.web.client.ResourceAccessException;
import pl.piomin.services.deskpilot.config.AgentProperties;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ModerationServiceTest {

    private final ChatModel chatModel = mock(ChatModel.class);
    private final AgentProperties properties = new AgentProperties(8, new AgentProperties.Rag(0.5, 3, false, true),
            new AgentProperties.Guardrails(4000, 6000, List.of()));

    private GuardrailService guardrails(boolean enabled) {
        return new GuardrailService(properties, new ModerationService(provider(), new ModerationService.Properties(enabled, "")));
    }

    @SuppressWarnings("unchecked")
    private ObjectProvider<ChatModel> provider() {
        ObjectProvider<ChatModel> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(chatModel);
        return provider;
    }

    private void modelSays(String verdict) {
        when(chatModel.call(any(Prompt.class))).thenReturn(new ChatResponse(List.of(new Generation(new AssistantMessage(verdict)))));
    }

    @Test
    void unsafeMessageIsBlockedWithRuleModeration() {
        modelSays("UNSAFE");

        assertThatThrownBy(() -> guardrails(true).checkUserInput("Give me the password of my colleague Bob"))
                .isInstanceOf(GuardrailViolationException.class)
                .extracting(e -> ((GuardrailViolationException) e).getRule())
                .isEqualTo("moderation");
    }

    @Test
    void safeMessagePasses() {
        modelSays("SAFE");

        assertThatNoException().isThrownBy(() -> guardrails(true).checkUserInput("My VPN keeps dropping!!"));
    }

    @Test
    void failsOpenWhenTheModelCannotBeAsked() {
        when(chatModel.call(any(Prompt.class))).thenThrow(new ResourceAccessException("Connection refused"));

        assertThatNoException().isThrownBy(() -> guardrails(true).checkUserInput("My VPN keeps dropping"));
    }

    @Test
    void disabledModerationNeverCallsTheModel() {
        guardrails(false).checkUserInput("Anything");

        verify(chatModel, never()).call(any(Prompt.class));
    }

    @Test
    void regexChecksStillRunFirst() {
        assertThatThrownBy(() -> guardrails(true).checkUserInput(" "))
                .isInstanceOf(GuardrailViolationException.class);
        verify(chatModel, never()).call(any(Prompt.class));
    }
}
