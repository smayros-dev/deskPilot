package pl.piomin.services.deskpilot.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.model.KnowledgeDocument;

public interface KnowledgeDocumentRepository extends JpaRepository<KnowledgeDocument, Long> {

    List<KnowledgeDocument> findAllByOrderByUploadedAtDesc();

    /** Documents left half-processed by a restart. */
    @Transactional
    @Modifying
    @Query("""
            update KnowledgeDocument d set d.status = pl.piomin.services.deskpilot.model.DocumentStatus.FAILED,
                   d.error = :error
            where d.status = pl.piomin.services.deskpilot.model.DocumentStatus.PROCESSING""")
    int failProcessing(String error);
}
