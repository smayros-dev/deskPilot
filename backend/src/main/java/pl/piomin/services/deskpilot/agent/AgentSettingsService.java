package pl.piomin.services.deskpilot.agent;

import java.util.HashSet;
import java.util.Set;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.tool.ExternalTools;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.config.AgentProperties;
import pl.piomin.services.deskpilot.config.SpecialistProperties;
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
    private final CloudProviderService cloudProviders;
    private final AgentSettings.Values defaults;

    public AgentSettingsService(AgentSettingsRepository repository, ModelCatalog modelCatalog, ToolRegistry toolRegistry,
                                CloudProviderService cloudProviders,
                                AgentProperties properties, SpecialistProperties specialists,
                                @Value("${spring.ai.ollama.chat.model}") String chatModel,
                                @Value("${spring.ai.ollama.chat.temperature:0.2}") double temperature,
                                @Value("${spring.ai.ollama.chat.think:false}") String think,
                                @Value("${agent.grounding-check:true}") boolean groundingCheck,
                                @Value("${agent.self-check:true}") boolean selfCheck,
                                @Value("${agent.vision-model:}") String visionModel) {
        this.repository = repository;
        this.modelCatalog = modelCatalog;
        this.toolRegistry = toolRegistry;
        this.cloudProviders = cloudProviders;
        this.defaults = new AgentSettings.Values(chatModel, temperature, 0.9, DEFAULT_CONTEXT_WINDOW,
                DEFAULT_MAX_OUTPUT_TOKENS, Boolean.parseBoolean(think), properties.maxIterations(),
                properties.rag().topK(), properties.rag().similarityThreshold(), groundingCheck, properties.rag().hybridSearch(), selfCheck,
                visionModel.isBlank() ? null : visionModel, "ollama", visionModel.isBlank() ? null : "ollama", Set.of(),
                toolRegistry.defaultApprovalTools(), null, specialists.enabled());
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
        validateChatModel(values.chatProvider(), values.chatModel());
        if (values.visionModel() != null && !values.visionModel().isBlank() && CloudProviderService.isCloud(values.visionProvider())) {
            if (!cloudModel(values.visionProvider(), values.visionModel().strip()).supportsVision()) {
                throw new IllegalArgumentException("Model '" + values.visionModel() + "' cannot read images (no vision capability)");
            }
        } else if (values.visionModel() != null && !values.visionModel().isBlank()) {
            ModelCatalog.ModelInfo vision = modelCatalog.find(values.visionModel().strip())
                    .orElseThrow(() -> new IllegalArgumentException("Vision model '" + values.visionModel()
                            + "' is not installed in Ollama. Run: ollama pull " + values.visionModel().strip()));
            if (!vision.supportsVision()) {
                throw new IllegalArgumentException("Model '" + vision.name() + "' cannot read images (no vision capability)");
            }
        }
        Set<String> unknown = new HashSet<>(values.disabledTools());
        unknown.addAll(values.approvalTools());
        unknown.removeAll(toolRegistry.names());
        // An MCP server may be offline for a while; its tools stay in the settings.
        unknown.removeIf(name -> name.startsWith(ExternalTools.NAME_PREFIX));
        if (!unknown.isEmpty()) {
            throw new IllegalArgumentException("Unknown tools: " + unknown);
        }
        AgentSettings settings = load();
        settings.apply(values);
        return settings;
    }

    /**
     * Checks that a model can serve as the agent's chat model: installed in Ollama or offered by an available cloud
     * provider, and able to call tools.
     *
     * @throws IllegalArgumentException otherwise
     */
    public void validateChatModel(String provider, String chatModel) {
        if (CloudProviderService.isCloud(provider)) {
            CloudProviderService.CloudModel model = cloudModel(provider, chatModel);
            if (!model.supportsTools()) {
                throw new IllegalArgumentException("Model '" + model.id() + "' does not support tool calling");
            }
        } else {
            ModelCatalog.ModelInfo model = modelCatalog.find(chatModel)
                    .orElseThrow(() -> new IllegalArgumentException("Model '" + chatModel
                            + "' is not installed in Ollama. Run: ollama pull " + chatModel));
            if (!model.supportsTools()) {
                throw new IllegalArgumentException("Model '" + model.name() + "' does not support tool calling");
            }
        }
    }

    /** The model must be offered by an enabled cloud provider that has a key. */
    private CloudProviderService.CloudModel cloudModel(String provider, String model) {
        if (!cloudProviders.isAvailable(provider)) {
            throw new IllegalArgumentException("Provider '" + provider + "' is not enabled or has no API key (Settings > Providers)");
        }
        return cloudProviders.models(provider).stream().filter(m -> m.id().equals(model)).findFirst()
                .orElseThrow(() -> new IllegalArgumentException("Model '" + model + "' is not offered by " + provider));
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
                v.hybridSearch(), v.selfCheck(), v.visionModel(),
                v.chatProvider(), v.visionProvider(), v.disabledTools(), v.approvalTools(), v.systemPrompt(), v.specialists());
    }
}
