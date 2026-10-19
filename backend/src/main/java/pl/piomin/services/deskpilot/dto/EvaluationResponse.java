package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import pl.piomin.services.deskpilot.model.Evaluation;
import pl.piomin.services.deskpilot.model.EvaluationResult;
import pl.piomin.services.deskpilot.model.EvaluationStatus;

/**
 * An evaluation with its scores. {@code results} is filled only when one evaluation is requested.
 *
 * @param passRate share of completed scenarios that passed (0..1)
 */
public record EvaluationResponse(
        String id,
        String model,
        EvaluationStatus status,
        int scenarioCount,
        int completed,
        int passed,
        double passRate,
        long avgLatencyMillis,
        long p95LatencyMillis,
        List<CategoryScore> categories,
        String startedBy,
        Instant startedAt,
        Instant finishedAt,
        String error,
        List<Result> results) {

    public record CategoryScore(String category, int scenarios, int passed) {
    }

    public record Result(String scenarioId, String category, String question, boolean passed, String expectedOutcome,
                         String outcome, List<String> tools, List<String> failures, String answer, String runId,
                         long latencyMillis, int iterations, long tokens) {

        static Result of(EvaluationResult r) {
            return new Result(r.getScenarioId(), r.getCategory(), r.getQuestion(), r.isPassed(), r.getExpectedOutcome(),
                    r.getOutcome(), split(r.getToolsUsed(), ","), split(r.getFailures(), "\n"), r.getAnswer(), r.getRunId(),
                    r.getLatencyMillis(), r.getIterations(), r.getTokens());
        }

        private static List<String> split(String text, String separator) {
            return text == null || text.isBlank() ? List.of() : Arrays.asList(text.split(separator));
        }
    }

    public static EvaluationResponse of(Evaluation evaluation, List<EvaluationResult> results, boolean withResults) {
        List<Long> latencies = results.stream().map(EvaluationResult::getLatencyMillis).sorted().toList();
        Map<String, int[]> byCategory = new TreeMap<>();
        for (EvaluationResult result : results) {
            int[] counts = byCategory.computeIfAbsent(result.getCategory(), c -> new int[2]);
            counts[0]++;
            counts[1] += result.isPassed() ? 1 : 0;
        }
        int passed = (int) results.stream().filter(EvaluationResult::isPassed).count();
        return new EvaluationResponse(
                evaluation.getId(),
                evaluation.getModel(),
                evaluation.getStatus(),
                evaluation.getScenarioCount(),
                results.size(),
                passed,
                results.isEmpty() ? 0 : (double) passed / results.size(),
                latencies.isEmpty() ? 0 : Math.round(latencies.stream().mapToLong(Long::longValue).average().orElse(0)),
                latencies.isEmpty() ? 0 : latencies.get(Math.max(0, (int) Math.ceil(latencies.size() * 0.95) - 1)),
                byCategory.entrySet().stream()
                        .map(e -> new CategoryScore(e.getKey(), e.getValue()[0], e.getValue()[1]))
                        .sorted(Comparator.comparing(CategoryScore::category))
                        .toList(),
                evaluation.getStartedBy(),
                evaluation.getStartedAt(),
                evaluation.getFinishedAt(),
                evaluation.getError(),
                withResults ? results.stream().map(Result::of).toList() : List.of());
    }
}
