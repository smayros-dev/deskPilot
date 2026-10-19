package pl.piomin.services.deskpilot;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Checks retrieval quality and the similarity threshold with real nomic-embed-text embeddings.
 * Runs only when OLLAMA_IT=true.
 */
@SpringBootTest(properties = "agent.rag.index-on-startup=true")
@Import(TestcontainersConfiguration.class)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "OLLAMA_IT", matches = "true")
class KnowledgeBaseSmokeTest {

    @Autowired
    private KnowledgeBaseService knowledgeBaseService;

    @Test
    void findsVpnArticleForParaphrasedQuestion() {
        List<KnowledgeBaseService.Hit> hits = knowledgeBaseService.search("I can't reach the intranet when working from home");

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().title()).containsIgnoringCase("VPN");
    }

    @Test
    void findsPrinterArticle() {
        List<KnowledgeBaseService.Hit> hits = knowledgeBaseService.search("my print job is stuck in the queue");

        assertThat(hits).isNotEmpty();
        assertThat(hits.getFirst().title()).containsIgnoringCase("printer");
    }

    @Test
    void offTopicQuestionHasNoRelevantHits() {
        assertThat(knowledgeBaseService.search("what is a good recipe for chocolate cake")).isEmpty();
    }
}
