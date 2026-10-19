package pl.piomin.services.deskpilot.repository;

import java.time.Instant;
import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.model.Evaluation;
import pl.piomin.services.deskpilot.model.EvaluationStatus;

public interface EvaluationRepository extends JpaRepository<Evaluation, String> {

    List<Evaluation> findAllByOrderByStartedAtDesc();

    boolean existsByStatus(EvaluationStatus status);

    @Transactional
    @Modifying
    @Query("update Evaluation e set e.completed = e.completed + 1, e.passed = e.passed + :passed where e.id = :id")
    int recordResult(String id, int passed);

    /** Moves a running evaluation to its final status; does nothing if it is no longer running (e.g. cancelled). */
    @Transactional
    @Modifying
    @Query("""
            update Evaluation e set e.status = :status, e.finishedAt = :at, e.error = :error
            where e.id = :id and e.status = pl.piomin.services.deskpilot.model.EvaluationStatus.RUNNING""")
    int finish(String id, EvaluationStatus status, Instant at, String error);

    /** Evaluations cut off by a restart. */
    @Transactional
    @Modifying
    @Query("""
            update Evaluation e set e.status = pl.piomin.services.deskpilot.model.EvaluationStatus.FAILED,
                   e.finishedAt = :at, e.error = :error
            where e.status = pl.piomin.services.deskpilot.model.EvaluationStatus.RUNNING""")
    int failRunning(Instant at, String error);
}
