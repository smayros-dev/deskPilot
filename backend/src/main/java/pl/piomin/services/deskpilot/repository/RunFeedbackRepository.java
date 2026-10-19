package pl.piomin.services.deskpilot.repository;

import java.util.List;
import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunFeedback;

public interface RunFeedbackRepository extends JpaRepository<RunFeedback, Long> {

    Optional<RunFeedback> findByRunId(String runId);

    List<RunFeedback> findTop10ByRatingOrderByUpdatedAtDesc(FeedbackRating rating);

    interface ModelFeedback {
        String getModel();

        long getUp();

        long getDown();
    }

    /** Ratings per model of the rated run, most rated first. */
    @Query("""
            select coalesce(r.model, 'unknown') as model,
                   sum(case when f.rating = pl.piomin.services.deskpilot.model.FeedbackRating.UP then 1 else 0 end) as up,
                   sum(case when f.rating = pl.piomin.services.deskpilot.model.FeedbackRating.DOWN then 1 else 0 end) as down
            from RunFeedback f, AgentRun r where r.id = f.runId and r.source = pl.piomin.services.deskpilot.model.RunSource.CHAT
            group by coalesce(r.model, 'unknown') order by count(f) desc""")
    List<ModelFeedback> totalsByModel();
}
