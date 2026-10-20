package pl.piomin.services.deskpilot.service;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/** Replaces Ollama's embedding model with deterministic bag-of-words vectors of the pgvector table's size. */
@TestConfiguration(proxyBeanMethods = false)
public class FakeEmbeddingConfiguration {

    @Bean
    @Primary
    public EmbeddingModel fakeEmbeddingModel() {
        return new FakeEmbeddingModel(768);
    }
}
