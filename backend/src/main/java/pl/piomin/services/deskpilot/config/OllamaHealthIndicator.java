package pl.piomin.services.deskpilot.config;

import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * Reports DOWN when Ollama is unreachable or one of the configured models has not been pulled,
 * so a missing {@code ollama pull} shows up in {@code /actuator/health} instead of as a failed chat.
 */
@Component("ollama")
public class OllamaHealthIndicator implements HealthIndicator {

    private final OllamaApi ollamaApi;
    private final List<String> requiredModels;

    public OllamaHealthIndicator(OllamaApi ollamaApi,
                                 @Value("${spring.ai.ollama.chat.model}") String chatModel,
                                 @Value("${spring.ai.ollama.embedding.model}") String embeddingModel) {
        this.ollamaApi = ollamaApi;
        this.requiredModels = List.of(chatModel, embeddingModel);
    }

    @Override
    public Health health() {
        Set<String> available;
        try {
            available = ollamaApi.listModels().models().stream()
                    .map(OllamaApi.Model::name)
                    .collect(Collectors.toSet());
        } catch (RuntimeException e) {
            return Health.down(e).withDetail("hint", "Is Ollama running? Start it with `ollama serve`.").build();
        }

        List<String> missing = requiredModels.stream()
                .filter(model -> !isAvailable(model, available))
                .toList();
        if (!missing.isEmpty()) {
            return Health.down()
                    .withDetail("missingModels", missing)
                    .withDetail("hint", "Run `ollama pull <model>` for each missing model.")
                    .build();
        }
        return Health.up().withDetail("models", requiredModels).build();
    }

    // Ollama reports untagged models as "<name>:latest".
    private static boolean isAvailable(String model, Set<String> available) {
        return available.contains(model) || (!model.contains(":") && available.contains(model + ":latest"));
    }
}
