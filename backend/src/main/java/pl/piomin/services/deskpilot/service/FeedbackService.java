package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;

/**
 * Users rate the answers of their own finished runs. Ratings feed the dashboard (satisfaction per model) and give
 * reviewers the negative answers worth turning into evaluation scenarios.
 */
@Service
@Transactional(readOnly = true)
public class FeedbackService {

    /** A rating together with the run it is about. */
    public record Rated(RunFeedback feedback, AgentRun run) {
    }

    private final RunFeedbackRepository feedbackRepository;
    private final AgentRunRepository runRepository;

    public FeedbackService(RunFeedbackRepository feedbackRepository, AgentRunRepository runRepository) {
        this.feedbackRepository = feedbackRepository;
        this.runRepository = runRepository;
    }

    /** Rates the run, or changes an earlier rating. Only the user who asked may rate, and only a finished run. */
    @Transactional
    public RunFeedback rate(String runId, String userId, FeedbackRating rating, String comment) {
        AgentRun run = ownRun(runId, userId);
        if (!run.isFinished()) {
            throw new ConflictException("Run " + runId + " is not finished yet");
        }
        String text = comment == null || comment.isBlank() ? null : comment.strip();
        Optional<RunFeedback> existing = feedbackRepository.findByRunId(runId);
        if (existing.isPresent()) {
            existing.get().change(rating, text);
            return existing.get();
        }
        return feedbackRepository.save(new RunFeedback(runId, userId, rating, text));
    }

    @Transactional
    public void remove(String runId, String userId) {
        ownRun(runId, userId);
        feedbackRepository.findByRunId(runId).ifPresent(feedbackRepository::delete);
    }

    public Optional<RunFeedback> find(String runId) {
        return feedbackRepository.findByRunId(runId);
    }

    /** The ten latest ratings of the given kind (typically 👎), newest first, with their runs. */
    public List<Rated> latest(FeedbackRating rating) {
        return feedbackRepository.findTop10ByRatingOrderByUpdatedAtDesc(rating).stream()
                .flatMap(feedback -> runRepository.findById(feedback.getRunId()).map(run -> new Rated(feedback, run)).stream())
                .toList();
    }

    // Someone else's run answers 404, like the other ownership checks: its existence is not revealed.
    private AgentRun ownRun(String runId, String userId) {
        return runRepository.findById(runId)
                .filter(run -> run.getUserId().equals(userId))
                .orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
    }
}
