package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;

public record RunResponse(
        String id,
        String sessionId,
        String userId,
        String model,
        String specialist,
        Experiment experiment,
        RunStatus status,
        String userInput,
        String answer,
        List<Step> steps,
        List<PendingApproval> pendingApprovals,
        Stats stats,
        Instant createdAt,
        Instant completedAt,
        Feedback feedback) {

    /**
     * The A/B experiment the run's conversation was assigned to (12m). Only sent to reviewers and admins, so users are
     * not told which variant they get; null outside experiments.
     */
    public record Experiment(String id, String name, String variant) {
    }

    /** The user's rating of the answer; null until they give one. */
    public record Feedback(FeedbackRating rating, String comment, Instant updatedAt) {
    }

    public record Step(long id, StepType type, String name, String content, boolean error, long durationMillis,
                       Instant createdAt) {
    }

    public record PendingApproval(String toolCallId, String toolName, String arguments) {
    }

    public record Stats(int iterations, int toolCalls, int toolErrors, long promptTokens, long completionTokens,
                        long activeMillis) {
    }
}
