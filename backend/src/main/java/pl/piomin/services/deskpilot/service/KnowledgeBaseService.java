package pl.piomin.services.deskpilot.service;

import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

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
 * Retrieval side of the agentic RAG: articles live in the database and are embedded into the vector store.
 * Hybrid search combines the semantic ranking with a full-text keyword ranking (Reciprocal Rank Fusion): an article
 * qualifies when it is semantically close (above the similarity threshold) or matches the query's keywords, which
 * catches short or product-specific questions ("install visual studio code") that embeddings score too low.
 */
@Service
@Transactional(readOnly = true)
public class KnowledgeBaseService {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeBaseService.class);
    private static final String ARTICLE_ID = "articleId";
    private static final String TITLE = "title";
    /** Candidates taken from each ranking before fusion. */
    static final int CANDIDATES = 20;
    /** Reciprocal Rank Fusion constant: dampens the weight of top ranks so both lists contribute. */
    static final int RRF_K = 60;

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
        return search(query, topK, similarityThreshold, properties.rag().hybridSearch());
    }

    /**
     * @param hybrid also rank by keywords (see class comment); when false, only hits above the similarity threshold
     *               are returned, ranked by similarity
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public List<Hit> search(String query, int topK, double similarityThreshold, boolean hybrid) {
        if (!hybrid) {
            return vectorStore.similaritySearch(SearchRequest.builder()
                            .query(query).topK(topK).similarityThreshold(similarityThreshold).build())
                    .stream().map(KnowledgeBaseService::toHit).flatMap(Optional::stream).toList();
        }
        List<Hit> semantic = vectorStore.similaritySearch(SearchRequest.builder()
                        .query(query).topK(CANDIDATES).similarityThreshold(0.0).build())
                .stream().map(KnowledgeBaseService::toHit).flatMap(Optional::stream).toList();
        List<Long> keyword = repository.keywordSearch(query, CANDIDATES).stream()
                .map(KnowledgeArticleRepository.KeywordHit::getId).toList();

        Map<Long, Double> fused = new HashMap<>();
        for (int rank = 0; rank < semantic.size(); rank++) {
            fused.merge(semantic.get(rank).articleId(), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        for (int rank = 0; rank < keyword.size(); rank++) {
            fused.merge(keyword.get(rank), 1.0 / (RRF_K + rank + 1), Double::sum);
        }
        Map<Long, Hit> semanticById = semantic.stream().collect(Collectors.toMap(Hit::articleId, h -> h, (a, b) -> a));
        Set<Long> keywordMatches = Set.copyOf(keyword);
        return fused.entrySet().stream()
                .filter(e -> keywordMatches.contains(e.getKey())
                        || semanticById.get(e.getKey()).score() >= similarityThreshold)
                .sorted(Map.Entry.<Long, Double>comparingByValue().reversed())
                .limit(topK)
                .map(e -> semanticById.containsKey(e.getKey()) ? Optional.of(semanticById.get(e.getKey())) : keywordOnlyHit(e.getKey()))
                .flatMap(Optional::stream)
                .toList();
    }

    /** An article outside the semantic candidates (possible in a large knowledge base), with no similarity score. */
    private Optional<Hit> keywordOnlyHit(long articleId) {
        return repository.findById(articleId)
                .map(article -> new Hit(article.getId(), article.getTitle(), embeddedText(article), 0.0));
    }

    public Optional<KnowledgeArticle> find(long id) {
        return repository.findById(id);
    }

    public List<KnowledgeArticle> findAll() {
        return repository.findAll();
    }

    private static Document toDocument(KnowledgeArticle article) {
        String id = UUID.nameUUIDFromBytes(("kb-" + article.getId()).getBytes(StandardCharsets.UTF_8)).toString();
        // Title and tags are part of the embedded text: they carry most of the topical signal.
        return new Document(id, embeddedText(article), Map.of(ARTICLE_ID, article.getId(), TITLE, article.getTitle()));
    }

    private static String embeddedText(KnowledgeArticle article) {
        return article.getTitle() + "\n" + "Keywords: " + article.getTags() + "\n\n" + article.getContent();
    }

    private static Optional<Hit> toHit(Document document) {
        Map<String, Object> metadata = document.getMetadata();
        long articleId = ((Number) metadata.get(ARTICLE_ID)).longValue();
        double score = document.getScore() == null ? 0.0 : document.getScore();
        return Optional.of(new Hit(articleId, (String) metadata.get(TITLE), document.getText(), score));
    }
}
