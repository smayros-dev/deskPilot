package pl.piomin.services.deskpilot.agent;

import java.util.List;

import com.openai.errors.OpenAIServiceException;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.exception.ModelProviderException;

/**
 * Sends a model call to the provider chosen in the settings (12s): the local Ollama model (the {@link ChatModel} bean,
 * with its circuit breaker) or a cloud provider. All agent model calls go through here.
 */
@Component
public class ModelGateway {

    private final ChatModel ollama;
    private final CloudProviderService cloud;
    private final ChatOptionsFactory chatOptionsFactory;

    ModelGateway(ChatModel ollama, CloudProviderService cloud, ChatOptionsFactory chatOptionsFactory) {
        this.ollama = ollama;
        this.cloud = cloud;
        this.chatOptionsFactory = chatOptionsFactory;
    }

    /** Calls {@code config.chatModel()} at {@code config.chatProvider()} with the given messages and tools. */
    public ChatResponse call(AgentConfig config, List<Message> messages, List<ToolCallback> tools) {
        if (!CloudProviderService.isCloud(config.chatProvider())) {
            return ollama.call(new Prompt(messages, chatOptionsFactory.create(config, tools)));
        }
        ChatModel model = cloud.chatModel(config.chatProvider());
        try {
            return model.call(new Prompt(messages, chatOptionsFactory.createCloud(config, tools, model)));
        } catch (OpenAIServiceException e) {
            throw new ModelProviderException(config.chatProvider(), e.statusCode(), e.getMessage());
        }
    }
}
