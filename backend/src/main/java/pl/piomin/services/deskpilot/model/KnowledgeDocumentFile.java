package pl.piomin.services.deskpilot.model;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The uploaded bytes of a {@link KnowledgeDocument}, kept to re-index it. */
@Entity
@Table(name = "knowledge_document_file")
public class KnowledgeDocumentFile {

    @Id
    private Long documentId;

    @Column(nullable = false, columnDefinition = "bytea")
    private byte[] content;

    protected KnowledgeDocumentFile() {
    }

    public KnowledgeDocumentFile(Long documentId, byte[] content) {
        this.documentId = documentId;
        this.content = content;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public byte[] getContent() {
        return content;
    }
}
