package pl.piomin.services.deskpilot.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.KnowledgeGapQuestion;

public interface KnowledgeGapQuestionRepository extends JpaRepository<KnowledgeGapQuestion, Long> {

    List<KnowledgeGapQuestion> findByGapIdInOrderByAskedAtDesc(Collection<Long> gapIds);
}
