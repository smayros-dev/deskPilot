package pl.piomin.services.deskpilot.service;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.io.Resource;
import org.springframework.core.io.ResourceLoader;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.config.AgentProperties;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.repository.KnowledgeArticleRepository;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/**
 * Seeds the knowledge base from {@code classpath:kb/articles.json} on first start, then embeds it
 * into the vector store. Embedding failures are logged, not fatal: the app still starts while
 * Ollama is down, and {@code /actuator/health} shows why search is unavailable.
 */
@Component
public class KnowledgeBaseInitializer implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseInitializer.class);
    private static final String SEED_LOCATION = "classpath:kb/articles.json";

    private final KnowledgeArticleRepository repository;
    private final KnowledgeBaseService knowledgeBaseService;
    private final AgentProperties properties;
    private final JsonMapper jsonMapper;
    private final ResourceLoader resourceLoader;

    public KnowledgeBaseInitializer(KnowledgeArticleRepository repository, KnowledgeBaseService knowledgeBaseService,
                                    AgentProperties properties, JsonMapper jsonMapper, ResourceLoader resourceLoader) {
        this.repository = repository;
        this.knowledgeBaseService = knowledgeBaseService;
        this.properties = properties;
        this.jsonMapper = jsonMapper;
        this.resourceLoader = resourceLoader;
    }

    record SeedArticle(String title, String tags, String content) {
    }

    @Override
    public void run(ApplicationArguments args) throws IOException {
        seedIfEmpty();
        if (properties.rag().indexOnStartup()) {
            try {
                knowledgeBaseService.indexAll();
            } catch (RuntimeException e) {
                LOG.error("Could not index the knowledge base; search_knowledge_base will return no results", e);
            }
        }
    }

    void seedIfEmpty() throws IOException {
        if (repository.count() > 0) {
            return;
        }
        Resource resource = resourceLoader.getResource(SEED_LOCATION);
        try (InputStream in = resource.getInputStream()) {
            List<SeedArticle> seed = jsonMapper.readValue(in, new TypeReference<>() { });
            repository.saveAll(seed.stream()
                    .map(a -> new KnowledgeArticle(a.title(), a.content(), a.tags()))
                    .toList());
            LOG.info("Seeded {} knowledge-base articles", seed.size());
        }
    }
}
