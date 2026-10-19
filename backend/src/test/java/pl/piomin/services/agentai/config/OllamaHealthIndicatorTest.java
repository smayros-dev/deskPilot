package pl.piomin.services.agentai.config;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.ai.ollama.api.OllamaApi;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class OllamaHealthIndicatorTest {

    private final OllamaApi ollamaApi = mock(OllamaApi.class);
    private final OllamaHealthIndicator indicator =
            new OllamaHealthIndicator(ollamaApi, "qwen3:8b", "nomic-embed-text");

    @Test
    void upWhenAllModelsArePulled() {
        givenModels("qwen3:8b", "nomic-embed-text:latest");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.UP);
    }

    @Test
    void downWhenModelIsMissing() {
        givenModels("nomic-embed-text:latest");

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("missingModels", List.of("qwen3:8b"));
    }

    @Test
    void downWhenOllamaIsUnreachable() {
        when(ollamaApi.listModels()).thenThrow(new ResourceAccessException("Connection refused"));

        Health health = indicator.health();

        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsKey("hint");
    }

    private void givenModels(String... names) {
        List<OllamaApi.Model> models = Arrays.stream(names)
                .map(name -> new OllamaApi.Model(name, name, null, null, null, null))
                .toList();
        when(ollamaApi.listModels()).thenReturn(new OllamaApi.ListModelResponse(models));
    }
}
