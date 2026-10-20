package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.model.ExperimentStatus;

/**
 * An A/B experiment with the results of both variants (12m). Rates are 0..1 over finished runs.
 *
 * @param bPercent share of new conversations sent to variant B
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
        List<VariantResult> variants) {

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
