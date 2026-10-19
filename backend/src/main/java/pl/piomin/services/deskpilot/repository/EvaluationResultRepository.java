package pl.piomin.services.deskpilot.repository;

import java.util.Collection;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.deskpilot.model.EvaluationResult;

public interface EvaluationResultRepository extends JpaRepository<EvaluationResult, Long> {

    List<EvaluationResult> findByEvaluationIdOrderByIdAsc(String evaluationId);

    List<EvaluationResult> findByEvaluationIdIn(Collection<String> evaluationIds);
}
