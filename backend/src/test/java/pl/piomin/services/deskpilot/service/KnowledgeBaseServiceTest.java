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
        // Flushed: the full-text column is computed by Postgres on insert and read by a native query.
        repository.saveAllAndFlush(List.of(
                new KnowledgeArticle("Connecting to the corporate VPN", "Install GlobalProtect and sign in.", "vpn,remote"),
                new KnowledgeArticle("Adding a network printer", "Open Printers & scanners and add PRN-3-02.", "printer,print"),
                new KnowledgeArticle("Installing software", "Approved applications are self-service in the Company Portal.",
                        "software,application,license")));
        AgentProperties properties = new AgentProperties(8,
                new AgentProperties.Rag(0.3, 3, false, true), new AgentProperties.Guardrails(4000, 6000, List.of()));
        service = new KnowledgeBaseService(repository,
                SimpleVectorStore.builder(new FakeEmbeddingModel()).build(), properties);
    }

    @Test
    void indexesAllArticles() {
        assertThat(service.indexAll()).isEqualTo(3);
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

    @Test
    void hybridSearchFindsArticlesByKeywordWhenEmbeddingsScoreThemTooLow() {
        service.indexAll();
        String question = "How can I install Visual Studio Code?";

        assertThat(service.search(question, 3, 0.3, false)).isEmpty();
        // "Install" also appears in the VPN article, so both are returned; the agent gets the top 3 and picks.
        assertThat(service.search(question, 3, 0.3, true)).extracting(KnowledgeBaseService.Hit::title)
                .contains("Installing software");
    }

    @Test
    void hybridSearchRanksArticlesMatchingBothWaysFirst() {
        service.indexAll();

        assertThat(service.search("printer print PRN scanners", 3, 0.3, true)).extracting(KnowledgeBaseService.Hit::title)
                .first().isEqualTo("Adding a network printer");
    }

    @Test
    void hybridSearchStillReturnsNothingForUnrelatedQuestions() {
        service.indexAll();

        assertThat(service.search("quarterly sales forecast spreadsheet", 3, 0.3, true)).isEmpty();
    }
}
