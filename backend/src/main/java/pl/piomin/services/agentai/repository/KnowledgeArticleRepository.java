package pl.piomin.services.agentai.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.agentai.model.KnowledgeArticle;

public interface KnowledgeArticleRepository extends JpaRepository<KnowledgeArticle, Long> {
}
