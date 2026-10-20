package pl.piomin.services.deskpilot.agent;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.config.AgentProperties;
import pl.piomin.services.deskpilot.model.AgentSettings;
import pl.piomin.services.deskpilot.repository.AgentSettingsRepository;

/**
 * Reads and updates the runtime agent settings. The first read stores defaults derived from
 * application.yml, so a fresh database behaves exactly like the configured application.
 */
@Service
@Transactional(readOnly = true)
public class AgentSettingsService {

    static final int DEFAULT_CONTEXT_WINDOW = 8192;
    static final int DEFAULT_MAX_OUTPUT_TOKENS = 2048;

    private final AgentSettingsRepository repository;
    private final ModelCatalog modelCatalog;
    private final ToolRegistry toolRegistry;
    private final AgentSettings.Values defaults;

    public AgentSettingsService(AgentSettingsRepository repository, ModelCatalog modelCatalog, ToolRegistry toolRegistry,
                                AgentProperties properties,
                                @Value("${spring.ai.ollama.chat.model}") String chatModel,
                                @Value("${spring.ai.ollama.chat.temperature:0.2}") double temperature,
                                @Value("${spring.ai.ollama.chat.think:false}") String think,
                                @Value("${agent.grounding-check:true}") boolean groundingCheck,
                                @Value("${agent.self-check:true}") boolean selfCheck) {
        this.repository = repository;
        this.modelCatalog = modelCatalog;
        this.toolRegistry = toolRegistry;
        this.defaults = new AgentSettings.Values(chatModel, temperature, 0.9, DEFAULT_CONTEXT_WINDOW,
                DEFAULT_MAX_OUTPUT_TOKENS, Boolean.parseBoolean(think), properties.maxIterations(),
                properties.rag().topK(), properties.rag().similarityThreshold(), groundingCheck, properties.rag().hybridSearch(), selfCheck, Set.of(),
                toolRegistry.defaultApprovalTools(), null);
    }

    /** Current settings as an immutable snapshot for one run. */
    @Transactional
    public AgentConfig current() {
        return toConfig(load().values());
    }

    @Transactional
    public AgentSettings get() {
        return load();
    }

    /**
     * @throws IllegalArgumentException if the model is not installed or cannot call tools, or a tool name is unknown
     */
    @Transactional
    public AgentSettings update(AgentSettings.Values values) {
        ModelCatalog.ModelInfo model = modelCatalog.find(values.chatModel())
                .orElseThrow(() -> new IllegalArgumentException("Model '" + values.chatModel()
                        + "' is not installed in Ollama. Run: ollama pull " + values.chatModel()));
        if (!model.supportsTools()) {
            throw new IllegalArgumentException("Model '" + model.name() + "' does not support tool calling");
        }
        Set<String> unknown = new HashSet<>(values.disabledTools());
        unknown.addAll(values.approvalTools());
        unknown.removeAll(toolRegistry.names());
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown tools: " + unknown);
        }
        AgentSettings settings = load();
        settings.apply(values);
        return settings;
    }

    @Transactional
    public AgentSettings reset() {
        AgentSettings settings = load();
        settings.apply(defaults);
        return settings;
    }

    private AgentSettings load() {
        return repository.findById(AgentSettings.SINGLETON_ID)
                .orElseGet(() -> repository.save(new AgentSettings(defaults)));
    }

    private static AgentConfig toConfig(AgentSettings.Values v) {
        return new AgentConfig(v.chatModel(), v.temperature(), v.topP(), v.contextWindow(), v.maxOutputTokens(),
                v.thinking(), v.maxIterations(), v.ragTopK(), v.ragSimilarityThreshold(), v.groundingCheck(),
                v.hybridSearch(), v.selfCheck(), v.disabledTools(), v.approvalTools(), v.systemPrompt());
    }
}
