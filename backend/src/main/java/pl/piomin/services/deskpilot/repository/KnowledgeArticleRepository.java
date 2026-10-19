package pl.piomin.services.deskpilot.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.KnowledgeArticle;

public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Long> {
}
