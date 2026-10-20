package pl.piomin.services.deskpilot.dto;

import java.util.List;

/**
 * Success metrics of the agent (the "measure" step of the roadmap). Rates are 0..1 and computed over
 * finished runs; runs waiting for approval are counted separately.
 */
public record AgentMetricsResponse(
        long totalRuns,
        long finishedRuns,
        long awaitingApproval,
        long completed,
        long escalated,
        long failed,
        double taskSuccessRate,
        double escalationRate,
        double avgLatencyMillis,
        long p95LatencyMillis,
        double avgIterations,
        long toolCalls,
        double toolErrorRate,
        long approvalsRequested,
        long approvalsRejected,
        long promptTokens,
        long completionTokens,
        long inputsBlockedSinceStart,
        List<ToolStats> tools,
        List<ModelStats> models,
        FeedbackStats feedback,
        List<SpecialistStats> specialists) {

    /**
     * Users' ratings of answers.
     *
     * @param satisfactionRate share of 👍 among all ratings (0..1), 0 when there is none
     */
    public record FeedbackStats(long up, long down, double satisfactionRate, List<ModelFeedback> models) {
    }

    public record ModelFeedback(String model, long up, long down, double satisfactionRate) {
    }

    /** Per-model comparison over finished runs: the data to choose a model on. */
    public record ModelStats(String model, long runs, double successRate, double avgLatencyMillis,
                             double avgIterations, double avgTokens) {
    }

    /** Per-specialist comparison over finished chat runs that were routed (12f). */
    public record SpecialistStats(String specialist, long runs, double successRate, double escalationRate,
                                  double avgLatencyMillis) {
    }

    public record ToolStats(String tool, long calls, long errors, double accuracy, double avgMillis) {
    }
}
