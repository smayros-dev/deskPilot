package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.exception.UnsupportedDocumentException;
import pl.piomin.services.deskpilot.model.DocumentFormat;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;
import pl.piomin.services.deskpilot.model.KnowledgeDocument;
import pl.piomin.services.deskpilot.model.KnowledgeDocumentFile;
import pl.piomin.services.deskpilot.repository.KnowledgeArticleRepository;
import pl.piomin.services.deskpilot.repository.KnowledgeDocumentFileRepository;
import pl.piomin.services.deskpilot.repository.KnowledgeDocumentRepository;

/**
 * Adds uploaded documents to the knowledge base. The upload is stored at once (status PROCESSING); parsing, chunking
 * and embedding (an Ollama call per chunk) then run on a virtual thread, outside any transaction except the short
 * one that replaces the document's chunks. Chunks are ordinary knowledge-base articles linked to their document, so
 * the hybrid search and the citations work on them unchanged. Assumes a single backend instance.
 */
@Service
@Transactional(readOnly = true)
public class DocumentIngestionService {

    private static final Logger LOG = LoggerFactory.getLogger(DocumentIngestionService.class);
    public static final long MAX_BYTES = 10L * 1024 * 1024;

    private final KnowledgeDocumentRepository documents;
    private final KnowledgeDocumentFileRepository files;
    private final KnowledgeArticleRepository articles;
    private final KnowledgeBaseService knowledgeBaseService;
    private final DocumentParser parser;
    private final TransactionTemplate transaction;

    public DocumentIngestionService(KnowledgeDocumentRepository documents, KnowledgeDocumentFileRepository files,
                                    KnowledgeArticleRepository articles, KnowledgeBaseService knowledgeBaseService,
                                    DocumentParser parser, PlatformTransactionManager transactionManager) {
        this.documents = documents;
        this.files = files;
        this.articles = articles;
        this.knowledgeBaseService = knowledgeBaseService;
        this.parser = parser;
        this.transaction = new TransactionTemplate(transactionManager);
    }

    /** Stores the file and starts processing it in the background. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public KnowledgeDocument upload(byte[] content, String fileName, String title, String uploadedBy) {
        DocumentFormat format = DocumentFormat.fromFileName(fileName)
                .orElseThrow(() -> new UnsupportedDocumentException("Unsupported file type '" + fileName
                        + "'. Upload a PDF, Markdown (.md), text (.txt) or HTML file."));
        if (content.length == 0) {
            throw new IllegalArgumentException("The file is empty");
        }
        if (content.length > MAX_BYTES) {
            throw new IllegalArgumentException("The file is larger than " + MAX_BYTES / (1024 * 1024) + " MB");
        }
        String documentTitle = title == null || title.isBlank() ? defaultTitle(fileName) : title.strip();
        KnowledgeDocument document = transaction.execute(status -> {
            KnowledgeDocument saved = documents.save(new KnowledgeDocument(truncate(documentTitle, 200),
                    truncate(fileName, 255), format, content.length, uploadedBy));
            files.save(new KnowledgeDocumentFile(saved.getId(), content));
            return saved;
        });
        startIngestion(document.getId());
        return document;
    }

    /** Parses and embeds the document again, e.g. after changing the chunking or when embedding failed. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public KnowledgeDocument reindex(long id) {
        KnowledgeDocument document = transaction.execute(status -> {
            KnowledgeDocument found = get(id);
            found.processing();
            return documents.save(found);
        });
        startIngestion(id);
        return document;
    }

    /** Removes the document, its chunks and their vectors. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public void delete(long id) {
        get(id);
        List<Long> chunkIds = articles.findByDocumentIdOrderByIdAsc(id).stream().map(KnowledgeArticle::getId).toList();
        knowledgeBaseService.removeFromIndex(chunkIds);
        // The chunks and the file go with the document (on delete cascade).
        transaction.executeWithoutResult(status -> documents.deleteById(id));
        LOG.info("Deleted document {} and its {} chunks", id, chunkIds.size());
    }

    public KnowledgeDocument get(long id) {
        return documents.findById(id).orElseThrow(() -> new NotFoundException("Document " + id + " not found"));
    }

    public List<KnowledgeDocument> list() {
        return documents.findAllByOrderByUploadedAtDesc();
    }

    /** Titles of the documents articles come from, by document id. */
    public Map<Long, String> titles() {
        return documents.findAll().stream().collect(Collectors.toMap(KnowledgeDocument::getId, KnowledgeDocument::getTitle));
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional
    public void failInterrupted() {
        int failed = documents.failProcessing("Interrupted by a backend restart; re-index the document");
        if (failed > 0) {
            LOG.warn("Marked {} document(s) interrupted by the previous shutdown as FAILED", failed);
        }
    }

    private void startIngestion(long id) {
        Thread.ofVirtual().name("ingest-document-" + id).start(() -> ingest(id));
    }

    void ingest(long id) {
        List<KnowledgeArticle> chunks = List.of();
        try {
            KnowledgeDocument document = get(id);
            byte[] content = files.findById(id).orElseThrow(() -> new NotFoundException("File of document " + id + " not found"))
                    .getContent();
            List<DocumentParser.Chunk> parsed = parser.parse(content, document.getFileName(), document.getFormat(),
                    document.getTitle());
            if (parsed.isEmpty()) {
                throw new IllegalArgumentException("No text found in the document (a scanned PDF needs OCR first)");
            }
            List<Long> previous = articles.findByDocumentIdOrderByIdAsc(id).stream().map(KnowledgeArticle::getId).toList();
            knowledgeBaseService.removeFromIndex(previous);
            String tags = truncate(document.getTitle().toLowerCase(Locale.ROOT), 300);
            chunks = transaction.execute(status -> {
                articles.deleteByDocumentId(id);
                return articles.saveAll(parsed.stream()
                        .map(chunk -> new KnowledgeArticle(chunk.title(), chunk.content(), tags, id))
                        .toList());
            });
            // Embedding calls the model for every chunk: outside the transaction.
            knowledgeBaseService.index(chunks);
            int count = chunks.size();
            updateDocument(id, d -> d.ready(count));
            LOG.info("Indexed document {} '{}' as {} chunks", id, document.getTitle(), count);
        } catch (RuntimeException e) {
            LOG.error("Could not index document {}", id, e);
            int count = chunks.size();
            String reason = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            // Chunks saved before the failure stay keyword-searchable; the message says what to fix or retry.
            updateDocument(id, d -> d.failed(count, reason));
        }
    }

    private void updateDocument(long id, Consumer<KnowledgeDocument> change) {
        transaction.executeWithoutResult(status -> documents.findById(id).ifPresent(document -> {
            change.accept(document);
            documents.save(document);
        }));
    }

    private static String defaultTitle(String fileName) {
        String name = fileName == null ? "Document" : fileName;
        int dot = name.lastIndexOf('.');
        return dot > 0 ? name.substring(0, dot) : name;
    }

    private static String truncate(String text, int max) {
        return text.length() <= max ? text : text.substring(0, max);
    }
}
