package pl.piomin.services.deskpilot.service;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.vectorstore.SimpleVectorStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.config.AgentProperties;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.repository.KnowledgeArticleRepository;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import(TestcontainersConfiguration.class)
class KnowledgeBaseServiceTest {

    @Autowired
    private KnowledgeArticleRepository repository;

    private KnowledgeBaseService service;

    @BeforeEach
    void setUp() {
        repository.saveAll(List.of(
                new KnowledgeArticle("Connecting to the corporate VPN", "Install GlobalProtect and sign in.", "vpn,remote"),
                new KnowledgeArticle("Adding a network printer", "Open Printers & scanners and add PRN-3-02.", "printer,print")));
        AgentProperties properties = new AgentProperties(8,
                new AgentProperties.Rag(0.3, 3, false), new AgentProperties.Guardrails(4000, 6000, List.of()));
        service = new KnowledgeBaseService(repository,
                SimpleVectorStore.builder(new FakeEmbeddingModel()).build(), properties);
    }

    @Test
    void indexesAllArticles() {
        assertThat(service.indexAll()).isEqualTo(2);
    }

    @Test
    void returnsMostRelevantArticleFirst() {
        service.indexAll();

        List<KnowledgeBaseService.Hit> hits = service.search("How do I connect to the VPN remote?");

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().title()).isEqualTo("Connecting to the corporate VPN");
        assertThat(hits.getFirst().score()).isBetween(0.3, 1.0);
    }

    @Test
    void returnsNothingBelowSimilarityThreshold() {
        service.indexAll();

        assertThat(service.search("quarterly sales forecast spreadsheet")).isEmpty();
    }

    @Test
    void reindexingDoesNotDuplicateArticles() {
        service.indexAll();
        service.indexAll();

        assertThat(service.search("printer print PRN")).hasSize(1);
    }
}
