package pl.piomin.services.deskpilot.service;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.config.AgentProperties;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.repository.KnowledgeArticleRepository;

/**
 * Retrieval side of the agentic RAG: articles live in the database and are embedded into the
 * vector store; searches only return hits above the configured similarity threshold so the agent
 * can tell "nothing relevant" apart from "weak match" and reformulate its query.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeBaseService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final String ARTICLE_ID = "articleId";
    private static final String TITLE = "title";

    private final KnowledgeArticleRepository repository;
    private final VectorStore vectorStore;
    private final AgentProperties properties;

    public KnowledgeBaseService(KnowledgeArticleRepository repository, VectorStore vectorStore,
                                AgentProperties properties) {
        this.repository = repository;
        this.vectorStore = vectorStore;
        this.properties = properties;
    }

    public record Hit(long articleId, String title, String content, double score) {
    }

    /**
     * Embeds every article into the vector store. Ids are derived from article ids, so re-indexing replaces entries.
     * Runs outside a transaction: the vector store writes on its own, and no connection is held while Ollama embeds.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public int indexAll() {
        List<Document> documents = repository.findAll().stream()
                .map(KnowledgeBaseService::toDocument)
                .toList();
        if (!documents.isEmpty()) {
            vectorStore.add(documents);
        }
        LOG.info("Indexed {} knowledge-base articles", documents.size());
        return documents.size();
    }

    /** Outside a transaction for the same reason: embedding the query calls the model. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Hit> search(String query) {
        return search(query, properties.rag().topK(), properties.rag().similarityThreshold());
    }

    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Hit> search(String query, int topK, double similarityThreshold) {
        SearchRequest request = SearchRequest.builder()
                .query(query)
                .topK(topK)
                .similarityThreshold(similarityThreshold)
                .build();
        return vectorStore.similaritySearch(request).stream()
                .map(KnowledgeBaseService::toHit)
                .toList();
    }

    public List<KnowledgeArticle> findAll() {
        return repository.findAll();
    }

    private static Document toDocument(KnowledgeArticle article) {
        String id = UUID.nameUUIDFromBytes(("kb-" + article.getId()).getBytes(StandardCharsets.UTF_8)).toString();
        // Title and tags are part of the embedded text: they carry most of the topical signal.
        String text = article.getTitle() + "\n" + "Keywords: " + article.getTags() + "\n\n" + article.getContent();
        return new Document(id, text, Map.of(ARTICLE_ID, article.getId(), TITLE, article.getTitle()));
    }

    private static Hit toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        long articleId = ((Number) metadata.get(ARTICLE_ID)).longValue();
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return new Hit(articleId, (String) metadata.get(TITLE), document.getText(), score);
    }
}
