package pl.piomin.services.deskpilot.service;

import java.util.List;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.agent.AgentMetrics;
import pl.piomin.services.deskpilot.dto.AgentMetricsResponse;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;

import static org.assertj.core.api.Assertions.assertThat;

@DataJpaTest
@Import({TestcontainersConfiguration.class, RunService.class})
class MetricsServiceTest {

    @Autowired
    private RunService runService;

    @Autowired
    private AgentRunRepository runRepository;

    @Autowired
    private AgentStepRepository stepRepository;

    @Autowired
    private RunFeedbackRepository feedbackRepository;

    private MetricsService metricsService;

    @BeforeEach
    void setUp() {
        metricsService = new MetricsService(runRepository, stepRepository, new AgentMetrics(new SimpleMeterRegistry()),
                feedbackRepository);
    }

    @Test
    void emptyDatabaseGivesZeroes() {
        AgentMetricsResponse metrics = metricsService.metrics();

        assertThat(metrics.totalRuns()).isZero();
        assertThat(metrics.taskSuccessRate()).isZero();
        assertThat(metrics.tools()).isEmpty();
    }

    @Test
    void computesRatesOverFinishedRuns() {
        finishedRun(RunStatus.COMPLETED, 1000, 2, "search_knowledge_base", false);
        finishedRun(RunStatus.COMPLETED, 3000, 2, "search_knowledge_base", true);
        finishedRun(RunStatus.ESCALATED, 8000, 3, "escalate_to_human", false);
        finishedRun(RunStatus.FAILED, 4000, 1, null, false);
        String paused = runService.start("s", "u", "open a ticket", "qwen3:8b").getId();
        runService.addStep(paused, StepType.APPROVAL_REQUESTED, "create_ticket", "{}", false, 0);
        runService.pauseForApproval(paused, 500);

        AgentMetricsResponse metrics = metricsService.metrics();

        assertThat(metrics.totalRuns()).isEqualTo(5);
        assertThat(metrics.finishedRuns()).isEqualTo(4);
        assertThat(metrics.awaitingApproval()).isEqualTo(1);
        assertThat(metrics.taskSuccessRate()).isEqualTo(0.5);
        assertThat(metrics.escalationRate()).isEqualTo(0.25);
        assertThat(metrics.avgLatencyMillis()).isEqualTo(4000.0);
        assertThat(metrics.p95LatencyMillis()).isEqualTo(8000);
        assertThat(metrics.avgIterations()).isEqualTo(2.0);
        assertThat(metrics.toolCalls()).isEqualTo(3);
        assertThat(metrics.toolErrorRate()).isEqualTo(0.333);
        assertThat(metrics.approvalsRequested()).isEqualTo(1);
        assertThat(metrics.tools()).first().satisfies(tool -> {
            assertThat(tool.tool()).isEqualTo("search_knowledge_base");
            assertThat(tool.calls()).isEqualTo(2);
            assertThat(tool.accuracy()).isEqualTo(0.5);
        });
    }

    @Test
    void percentileUsesNearestRank() {
        assertThat(MetricsService.percentile(List.of(), 0.95)).isZero();
        assertThat(MetricsService.percentile(List.of(10L), 0.95)).isEqualTo(10);
        assertThat(MetricsService.percentile(List.of(1L, 2L, 3L, 4L, 5L, 6L, 7L, 8L, 9L, 10L), 0.5)).isEqualTo(5);
    }

    private void finishedRun(RunStatus status, long activeMillis, int modelCalls, String tool, boolean toolError) {
        String runId = runService.start("s", "u", "question", "qwen3:8b").getId();
        for (int i = 0; i < modelCalls; i++) {
            runService.recordModelCall(runId, 100, 10);
        }
        if (tool != null) {
            runService.recordToolCall(runId, toolError);
            runService.addStep(runId, StepType.TOOL_RESULT, tool, "result", toolError, 50);
        }
        runService.finish(runId, status, "answer", activeMillis);
    }
}
