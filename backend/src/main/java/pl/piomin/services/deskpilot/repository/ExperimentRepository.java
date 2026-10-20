package pl.piomin.services.deskpilot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.Experiment;
import pl.piomin.services.deskpilot.model.ExperimentStatus;

public interface ExperimentRepository extends JpaRepository<Experiment, String> {

    Optional<Experiment> findFirstByStatus(ExperimentStatus status);

    List<Experiment> findAllByOrderByStartedAtDesc();

    @Query("select count(e) > 0 from Experiment e where e.aPromptId = :promptId or e.bPromptId = :promptId")
    boolean usesPrompt(Long promptId);
}
