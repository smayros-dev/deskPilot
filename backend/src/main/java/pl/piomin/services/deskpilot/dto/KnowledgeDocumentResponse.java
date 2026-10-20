package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

import pl.piomin.services.deskpilot.model.DocumentFormat;
import pl.piomin.services.deskpilot.model.DocumentStatus;
import pl.piomin.services.deskpilot.model.KnowledgeDocument;

/**
 * @param chunks knowledge-base articles the document was split into
 * @param error  why processing failed, when {@code status} is FAILED
 */
public record KnowledgeDocumentResponse(long id, String title, String fileName, DocumentFormat format, long sizeBytes,
                                        DocumentStatus status, int chunks, String error, String uploadedBy,
                                        Instant uploadedAt, Instant indexedAt) {

    public static KnowledgeDocumentResponse of(KnowledgeDocument d) {
        return new KnowledgeDocumentResponse(d.getId(), d.getTitle(), d.getFileName(), d.getFormat(), d.getSizeBytes(),
                d.getStatus(), d.getChunks(), d.getError(), d.getUploadedBy(), d.getUploadedAt(), d.getIndexedAt());
    }
}
