package pl.piomin.services.deskpilot.guardrail;

import java.util.List;
import java.util.Locale;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.stereotype.Service;

/**
 * Optional model-based moderation (12n), after the regex checks of {@link GuardrailService}: a short, tool-less
 * classification of the user's message before the agent works on it. Off by default because it costs one model call
 * per message on a CPU. Fails open: if the model cannot be asked, the message goes through (and the error is logged),
 * so moderation never takes the helpdesk down.
 */
@Service
public class ModerationService {

    /**
     * @param enabled ask the model to classify each message
     * @param model   model to classify with; empty = the chat model's default
     */
    @ConfigurationProperties("agent.moderation")
    public record Properties(@DefaultValue("false") boolean enabled, @DefaultValue("") String model) {
    }

    static final String PROMPT = """
            You moderate messages sent to a company's IT helpdesk assistant.
            Reply UNSAFE if the message asks for or contains: harassment or hate, self-harm, help with illegal \
            activity or with attacking computer systems, or another person's personal data or credentials.
            Otherwise reply SAFE. Ordinary IT problems, even urgent or angry ones, are SAFE.
            Reply with one word: SAFE or UNSAFE.""";

    private static final Logger LOG = LoggerFactory.getLogger(ModerationService.class);

    // Looked up when first needed: the model's own tool wiring depends (through the tools) on the guardrails.
    private final ObjectProvider<ChatModel> chatModel;
    private final Properties properties;

    public ModerationService(ObjectProvider<ChatModel> chatModel, Properties properties) {
        this.chatModel = chatModel;
        this.properties = properties;
    }

    public boolean enabled() {
        return properties.enabled();
    }

    /** @return true when the model judged the message unsafe; false when safe, disabled or the call failed */
    public boolean isUnsafe(String message) {
        if (!properties.enabled()) {
            return false;
        }
        try {
            ChatModel model = chatModel.getObject();
            ChatResponse response = model.call(new Prompt(List.of(new SystemMessage(PROMPT), new UserMessage(message)),
                    options(model)));
            String verdict = response.getResult() == null ? "" : String.valueOf(response.getResult().getOutput().getText());
            return verdict.strip().toUpperCase(Locale.ROOT).startsWith("UNSAFE");
        } catch (RuntimeException e) {
            LOG.warn("Moderation unavailable, message allowed: {}", e.getMessage());
            return false;
        }
    }

    private ChatOptions options(ChatModel model) {
        ChatOptions defaults = model.getDefaultOptions();
        if (defaults instanceof OllamaChatOptions ollama) {
            OllamaChatOptions.Builder builder = ollama.mutate().temperature(0.0).numPredict(5).disableThinking();
            if (!properties.model().isBlank()) {
                builder.model(properties.model());
            }
            return builder.build();
        }
        return defaults;
    }
}
