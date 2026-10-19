package pl.piomin.services.deskpilot.agent;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.model.tool.ToolCallingChatOptions;
import org.springframework.ai.ollama.api.OllamaChatOptions;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;

/**
 * Turns the run's {@link AgentConfig} into per-request chat options. Starts from the model's configured
 * defaults (runtime options replace them in Spring AI 2.0) and adds Ollama-only knobs when the
 * model is served by Ollama; any other provider still gets the portable options.
 */
@Component
public class ChatOptionsFactory {

    private final ChatModel chatModel;

    public ChatOptionsFactory(ChatModel chatModel) {
        this.chatModel = chatModel;
    }

    public ToolCallingChatOptions create(AgentConfig config, List<ToolCallback> tools) {
        if (chatModel.getDefaultOptions() instanceof OllamaChatOptions defaults) {
            OllamaChatOptions.Builder builder = defaults.mutate()
                    .model(config.chatModel())
                    .temperature(config.temperature())
                    .topP(config.topP())
                    .numCtx(config.contextWindow())
                    .numPredict(config.maxOutputTokens())
                    .toolCallbacks(tools);
            if (config.thinking()) {
                builder.enableThinking();
            } else {
                builder.disableThinking();
            }
            return builder.build();
        }
        ToolCallingChatOptions.Builder<?> builder = chatModel.getDefaultOptions() instanceof ToolCallingChatOptions defaults
                ? defaults.mutate()
                : ToolCallingChatOptions.builder();
        return builder.model(config.chatModel())
                .temperature(config.temperature())
                .topP(config.topP())
                .maxTokens(config.maxOutputTokens())
                .toolCallbacks(tools)
                .build();
    }
}
