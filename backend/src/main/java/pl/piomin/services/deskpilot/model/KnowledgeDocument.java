package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A document uploaded to the knowledge base; its chunks are {@link KnowledgeArticle}s with this document's id. */
@Entity
@Table(name = "knowledge_document")
public class KnowledgeDocument {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentFormat format;

    private long sizeBytes;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private DocumentStatus status;

    private int chunks;

    @Column(columnDefinition = "text")
    private String error;

    @Column(nullable = false, length = 64)
    private String uploadedBy;

    @Column(nullable = false)
    private Instant uploadedAt;

    private Instant indexedAt;

    protected KnowledgeDocument() {
    }

    public KnowledgeDocument(String title, String fileName, DocumentFormat format, long sizeBytes, String uploadedBy) {
        this.title = title;
        this.fileName = fileName;
        this.format = format;
        this.sizeBytes = sizeBytes;
        this.uploadedBy = uploadedBy;
        this.uploadedAt = Instant.now();
        this.status = DocumentStatus.PROCESSING;
    }

    public void processing() {
        this.status = DocumentStatus.PROCESSING;
        this.error = null;
    }

    public void ready(int chunks) {
        this.status = DocumentStatus.READY;
        this.chunks = chunks;
        this.error = null;
        this.indexedAt = Instant.now();
    }

    public void failed(int chunks, String error) {
        this.status = DocumentStatus.FAILED;
        this.chunks = chunks;
        this.error = error;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public String getFileName() {
        return fileName;
    }

    public DocumentFormat getFormat() {
        return format;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public DocumentStatus getStatus() {
        return status;
    }

    public int getChunks() {
        return chunks;
    }

    public String getError() {
        return error;
    }

    public String getUploadedBy() {
        return uploadedBy;
    }

    public Instant getUploadedAt() {
        return uploadedAt;
    }

    public Instant getIndexedAt() {
        return indexedAt;
    }
}
