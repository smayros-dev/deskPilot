package pl.piomin.services.agentai.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.agentai.exception.NotFoundException;
import pl.piomin.services.agentai.model.AgentRun;
import pl.piomin.services.agentai.model.AgentStep;
import pl.piomin.services.agentai.model.RunStatus;
import pl.piomin.services.agentai.model.StepType;
import pl.piomin.services.agentai.repository.AgentRunRepository;
import pl.piomin.services.agentai.repository.AgentStepRepository;

/**
 * Persists runs and their traces. Every method is its own short transaction so that no database
 * transaction is held open while the agent waits on the model.
 */
@Service
@Transactional(readOnly = true)
public class RunService {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;

    public RunService(AgentRunRepository runRepository, AgentStepRepository stepRepository) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
    }

    @Transactional
    public AgentRun start(String sessionId, String userId, String userInput, String model) {
        AgentRun run = runRepository.save(new AgentRun(sessionId, userId, userInput, model));
        stepRepository.save(new AgentStep(run.getId(), StepType.USER_INPUT, null, userInput, false, 0));
        return run;
    }

    @Transactional
    public AgentStep addStep(String runId, StepType type, String name, String content, boolean error, long durationMillis) {
        return stepRepository.save(new AgentStep(runId, type, name, content, error, durationMillis));
    }

    /** @return the number of model calls made in this run so far */
    @Transactional
    public int recordModelCall(String runId, long promptTokens, long completionTokens) {
        AgentRun run = get(runId);
        run.recordModelCall(promptTokens, completionTokens);
        return run.getIterations();
    }

    @Transactional
    public void recordToolCall(String runId, boolean error) {
        get(runId).recordToolCall(error);
    }

    @Transactional
    public void pauseForApproval(String runId, long activeMillis) {
        AgentRun run = get(runId);
        run.addActiveMillis(activeMillis);
        run.pauseForApproval();
    }

    @Transactional
    public void resume(String runId) {
        get(runId).resume();
    }

    @Transactional
    public AgentRun finish(String runId, RunStatus status, String answer, long activeMillis) {
        AgentRun run = get(runId);
        run.addActiveMillis(activeMillis);
        run.finish(status, answer);
        return run;
    }

    public AgentRun get(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
    }

    public List<AgentRun> findBySession(String sessionId) {
        return runRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<AgentStep> steps(String runId) {
        return stepRepository.findByRunIdOrderByIdAsc(runId);
    }
}
