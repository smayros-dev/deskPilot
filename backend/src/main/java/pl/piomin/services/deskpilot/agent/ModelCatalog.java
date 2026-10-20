package pl.piomin.services.deskpilot.agent;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.stereotype.Service;

/**
 * Models installed in the local Ollama, with their capabilities (from {@code /api/show}), so the UI only
 * offers chat models that can call tools.
 */
@Service
public class ModelCatalog {

    private static final Logger LOG = LoggerFactory.getLogger(ModelCatalog.class);

    private final OllamaApi ollamaApi;
    /** Capabilities never change for a given model digest, so they are looked up once. */
    private final Map<String, List<String>> capabilitiesByDigest = new ConcurrentHashMap<>();

    public ModelCatalog(OllamaApi ollamaApi) {
        this.ollamaApi = ollamaApi;
    }

    public record ModelInfo(String name, long sizeBytes, String parameterSize, String family,
                            List<String> capabilities, boolean supportsTools, boolean supportsThinking,
                            boolean chatModel,
                            boolean supportsVision) {

        public ModelInfo(String name, long sizeBytes, String parameterSize, String family, List<String> capabilities,
                         boolean supportsTools, boolean supportsThinking, boolean chatModel) {
            this(name, sizeBytes, parameterSize, family, capabilities, supportsTools, supportsThinking, chatModel,
                    capabilities.contains("vision"));
        }
    }

    public List<ModelInfo> list() {
        return ollamaApi.listModels().models().stream()
                .map(this::toInfo)
                .sorted(Comparator.comparing(ModelInfo::chatModel).reversed().thenComparing(ModelInfo::name))
                .toList();
    }

    public Optional<ModelInfo> find(String name) {
        return list().stream().filter(m -> m.name().equals(name) || m.name().equals(name + ":latest")).findFirst();
    }

    private ModelInfo toInfo(OllamaApi.Model model) {
        List<String> capabilities = capabilitiesByDigest.computeIfAbsent(
                model.digest() == null ? model.name() : model.digest(), digest -> fetchCapabilities(model.name()));
        OllamaApi.Model.Details details = model.details();
        return new ModelInfo(model.name(),
                model.size() == null ? 0 : model.size(),
                details == null ? null : details.parameterSize(),
                details == null ? null : details.family(),
                capabilities,
                capabilities.contains("tools"),
                capabilities.contains("thinking"),
                capabilities.contains("completion"));
    }

    private List<String> fetchCapabilities(String name) {
        try {
            List<String> capabilities = ollamaApi.showModel(new OllamaApi.ShowModelRequest(name)).capabilities();
            return capabilities == null ? List.of() : List.copyOf(capabilities);
        } catch (RuntimeException e) {
            LOG.warn("Could not read capabilities of model {}", name, e);
            return List.of();
        }
    }
}
