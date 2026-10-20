package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.model.ExperimentStatus;

/**
 * An A/B experiment with the results of both variants (12m). Rates are 0..1 over finished runs.
 *
 * @param bPercent   share of new conversations sent to variant B
 * @param stopReason why DeskPilot stopped it itself (provider disabled, repeated provider errors); null otherwise
 * @param comparison whether B differs from A beyond chance, for the success rate and the share of 👍
 */
public record ExperimentResponse(
        String id,
        String name,
        ExperimentStatus status,
        int bPercent,
        String startedBy,
        Instant startedAt,
        String stoppedBy,
        Instant stoppedAt,
        String stopReason,
        List<VariantResult> variants,
        Comparison comparison) {

    /** Verdict at the 95 % level, B compared with A. */
    public enum Verdict {
        /** A variant has fewer finished runs (or ratings) than {@code agent.experiments.min-sample}. */
        NOT_ENOUGH_DATA,
        NO_SIGNIFICANT_DIFFERENCE,
        B_BETTER,
        B_WORSE
    }

    /**
     * @param test    "z" (two-proportion z-test) or "fisher" (Fisher's exact test, for small counts); null without data
     * @param pValue  two-sided p-value; null when there is not enough data
     */
    public record Significance(long samplesA, long samplesB, double rateA, double rateB, String test, Double pValue,
                               Verdict verdict) {
    }

    /** @param minSample finished runs (or ratings) each variant needs before a verdict is given */
    public record Comparison(int minSample, Significance success, Significance satisfaction) {
    }

    /**
     * @param promptVersionId   null when the variant uses the prompt from the settings
     * @param satisfactionRate  share of 👍 among the ratings, 0 when there is none
     */
    public record VariantResult(
            String variant,
            String provider,
            String model,
            Long promptVersionId,
            String promptVersionName,
            long runs,
            long finished,
            double successRate,
            double escalationRate,
            double avgLatencyMillis,
            long p95LatencyMillis,
            double avgTokens,
            long up,
            long down,
            double satisfactionRate) {
    }
}
