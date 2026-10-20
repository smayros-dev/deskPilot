package pl.piomin.services.deskpilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.KnowledgeDocumentFile;

public interface KnowledgeDocumentFileRepository extends JpaRepository<KnowledgeDocumentFile, Long> {
}
