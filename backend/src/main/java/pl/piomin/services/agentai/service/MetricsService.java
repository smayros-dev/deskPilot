package pl.piomin.services.agentai.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.function.ToLongFunction;
import java.util.stream.Collectors;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.agentai.agent.AgentMetrics;
import pl.piomin.services.agentai.dto.AgentMetricsResponse;
import pl.piomin.services.agentai.model.RunStatus;
import pl.piomin.services.agentai.model.StepType;
import pl.piomin.services.agentai.repository.AgentRunRepository;
import pl.piomin.services.agentai.repository.AgentRunRepository.StatusTotals;
import pl.piomin.services.agentai.repository.AgentStepRepository;

/** Computes success metrics from the persisted runs, so they survive restarts (unlike in-memory meters). */
@Service
@Transactional(readOnly = true)
public class MetricsService {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final AgentMetrics agentMetrics;

    public MetricsService(AgentRunRepository runRepository, AgentStepRepository stepRepository,
                          AgentMetrics agentMetrics) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.agentMetrics = agentMetrics;
    }

    public AgentMetricsResponse metrics() {
        Map<RunStatus, StatusTotals> byStatus = runRepository.totalsByStatus().stream()
                .collect(Collectors.toMap(StatusTotals::getStatus, Function.identity()));
        List<StatusTotals> finished = byStatus.values().stream()
                .filter(t -> t.getStatus() != RunStatus.RUNNING && t.getStatus() != RunStatus.AWAITING_APPROVAL)
                .toList();

        long finishedRuns = sum(finished, StatusTotals::getRuns);
        long completed = runs(byStatus, RunStatus.COMPLETED);
        long escalated = runs(byStatus, RunStatus.ESCALATED);
        long toolCalls = sum(byStatus.values(), StatusTotals::getToolCalls);
        long toolErrors = sum(byStatus.values(), StatusTotals::getToolErrors);

        List<AgentMetricsResponse.ToolStats> tools = stepRepository.toolTotals().stream()
                .map(t -> new AgentMetricsResponse.ToolStats(t.getTool(), t.getCalls(), t.getErrors(),
                        ratio(t.getCalls() - t.getErrors(), t.getCalls()), Math.round(t.getAvgMillis())))
                .toList();

        List<AgentMetricsResponse.ModelStats> models = runRepository.finishedTotalsByModel().stream()
                .map(m -> new AgentMetricsResponse.ModelStats(m.getModel(), m.getRuns(), ratio(m.getCompleted(), m.getRuns()),
                        average(m.getActiveMillis(), m.getRuns()), average(m.getIterations(), m.getRuns()),
                        average(m.getTokens(), m.getRuns())))
                .toList();

        return new AgentMetricsResponse(
                sum(byStatus.values(), StatusTotals::getRuns),
                finishedRuns,
                runs(byStatus, RunStatus.AWAITING_APPROVAL),
                completed,
                escalated,
                runs(byStatus, RunStatus.FAILED),
                ratio(completed, finishedRuns),
                ratio(escalated, finishedRuns),
                average(sum(finished, StatusTotals::getActiveMillis), finishedRuns),
                percentile(runRepository.finishedRunLatencies(), 0.95),
                average(sum(finished, StatusTotals::getIterations), finishedRuns),
                toolCalls,
                ratio(toolErrors, toolCalls),
                stepRepository.countByType(StepType.APPROVAL_REQUESTED),
                stepRepository.countByTypeAndName(StepType.APPROVAL_DECISION, "REJECTED"),
                sum(byStatus.values(), StatusTotals::getPromptTokens),
                sum(byStatus.values(), StatusTotals::getCompletionTokens),
                (long) agentMetrics.guardrailBlockedTotal(),
                tools,
                models);
    }

    private static long runs(Map<RunStatus, StatusTotals> byStatus, RunStatus status) {
        StatusTotals totals = byStatus.get(status);
        return totals == null ? 0 : totals.getRuns();
    }

    private static long sum(Collection<StatusTotals> totals, ToLongFunction<StatusTotals> field) {
        return totals.stream().mapToLong(field).sum();
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 1000.0;
    }

    private static double average(long total, long count) {
        return count == 0 ? 0 : Math.round(10.0 * total / count) / 10.0;
    }

    /** Nearest-rank percentile over values sorted ascending. */
    static long percentile(List<Long> sortedValues, double percentile) {
        if (sortedValues.isEmpty()) {
            return 0;
        }
        int rank = (int) Math.ceil(percentile * sortedValues.size());
        return sortedValues.get(Math.max(0, rank - 1));
    }
}
