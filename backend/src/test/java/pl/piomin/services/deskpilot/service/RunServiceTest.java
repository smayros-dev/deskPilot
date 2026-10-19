package pl.piomin.services.deskpilot.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.deskpilot.TestcontainersConfiguration;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

@DataJpaTest
@Import({TestcontainersConfiguration.class, RunService.class})
class RunServiceTest {

    @Autowired
    private RunService runService;

    @Test
    void startRecordsRunAndUserInputStep() {
        AgentRun run = runService.start("s1", "alice", "My VPN is down", "qwen3:8b");

        assertThat(run.getStatus()).isEqualTo(RunStatus.RUNNING);
        assertThat(runService.steps(run.getId()))
                .extracting(AgentStep::getType, AgentStep::getContent)
                .containsExactly(tuple(StepType.USER_INPUT, "My VPN is down"));
    }

    @Test
    void tracksIterationsTokensAndToolCalls() {
        String runId = runService.start("s1", "alice", "hi", "qwen3:8b").getId();

        runService.recordModelCall(runId, 100, 20);
        int iterations = runService.recordModelCall(runId, 150, 30);
        runService.recordToolCall(runId, false);
        runService.recordToolCall(runId, true);

        AgentRun run = runService.get(runId);
        assertThat(iterations).isEqualTo(2);
        assertThat(run.getPromptTokens()).isEqualTo(250);
        assertThat(run.getCompletionTokens()).isEqualTo(50);
        assertThat(run.getToolCalls()).isEqualTo(2);
        assertThat(run.getToolErrors()).isEqualTo(1);
    }

    @Test
    void pauseAndFinishAccumulateActiveTime() {
        String runId = runService.start("s1", "alice", "create a ticket", "qwen3:8b").getId();

        runService.pauseForApproval(runId, 1200);
        assertThat(runService.get(runId).getStatus()).isEqualTo(RunStatus.AWAITING_APPROVAL);

        runService.resume(runId);
        AgentRun run = runService.finish(runId, RunStatus.COMPLETED, "Done", 800);

        assertThat(run.getStatus()).isEqualTo(RunStatus.COMPLETED);
        assertThat(run.getActiveMillis()).isEqualTo(2000);
        assertThat(run.getCompletedAt()).isNotNull();
        assertThat(run.isFinished()).isTrue();
    }

    @Test
    void findsRunsBySession() {
        runService.start("s1", "alice", "first", "qwen3:8b");
        runService.start("s1", "alice", "second", "qwen3:8b");
        runService.start("s2", "bob", "other", "qwen3:8b");

        assertThat(runService.findBySession("s1"))
                .extracting(AgentRun::getUserInput)
                .containsExactly("first", "second");
    }

    @Test
    void unknownRunIsNotFound() {
        assertThatThrownBy(() -> runService.get("missing"))
                .isInstanceOf(NotFoundException.class);
    }
}
