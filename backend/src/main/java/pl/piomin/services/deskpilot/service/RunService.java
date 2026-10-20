package pl.piomin.services.deskpilot.service;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.events.RunStatusChanged;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunSource;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentStepRepository;

/**
 * Persists runs and their traces. Every method is its own short transaction so that no database
 * transaction is held open while the agent waits on the model.
 */
@Service
@Transactional(readOnly = true)
public class RunService {

    private final AgentRunRepository runRepository;
    private final AgentStepRepository stepRepository;
    private final ApplicationEventPublisher events;

    public RunService(AgentRunRepository runRepository, AgentStepRepository stepRepository,
                      ApplicationEventPublisher events) {
        this.runRepository = runRepository;
        this.stepRepository = stepRepository;
        this.events = events;
    }

    @Transactional
    public AgentRun start(String sessionId, String userId, String userInput, String model) {
        return start(sessionId, userId, userInput, model, RunSource.CHAT);
    }

    @Transactional
    public AgentRun start(String sessionId, String userId, String userInput, String model, RunSource source) {
        AgentRun run = runRepository.save(new AgentRun(sessionId, userId, userInput, model, source));
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
        statusChanged(run);
    }

    @Transactional
    public void resume(String runId) {
        AgentRun run = get(runId);
        run.resume();
        statusChanged(run);
    }

    @Transactional
    public AgentRun finish(String runId, RunStatus status, String answer, long activeMillis) {
        AgentRun run = get(runId);
        run.addActiveMillis(activeMillis);
        run.finish(status, answer);
        statusChanged(run);
        return run;
    }

    /** Live updates for the browser (delivered after commit); evaluation runs stay out of them. */
    private void statusChanged(AgentRun run) {
        if (run.getSource() == RunSource.CHAT) {
            events.publishEvent(new RunStatusChanged(run.getId()));
        }
    }

    public AgentRun get(String runId) {
        return runRepository.findById(runId)
                .orElseThrow(() -> new NotFoundException("Run " + runId + " not found"));
    }

    /** Runs still in progress or paused, e.g. left behind when the application stopped. */
    public List<AgentRun> findUnfinished() {
        return runRepository.findByStatusIn(List.of(RunStatus.RUNNING, RunStatus.AWAITING_APPROVAL));
    }

    /** The reviewers' queue, oldest first. */
    public List<AgentRun> findAwaitingApproval() {
        return runRepository.findByStatusAndSourceOrderByCreatedAtAsc(RunStatus.AWAITING_APPROVAL, RunSource.CHAT);
    }

    /** Number of runs per session, for the given sessions (sessions without runs are absent). */
    public Map<String, Long> countBySession(Collection<String> sessionIds) {
        if (sessionIds.isEmpty()) {
            return Map.of();
        }
        return runRepository.countBySession(sessionIds).stream()
                .collect(Collectors.toMap(AgentRunRepository.SessionRunCount::getSessionId,
                        AgentRunRepository.SessionRunCount::getRuns));
    }

    public List<AgentRun> findBySession(String sessionId) {
        return runRepository.findBySessionIdOrderByCreatedAtAsc(sessionId);
    }

    public List<AgentStep> steps(String runId) {
        return stepRepository.findByRunIdOrderByIdAsc(runId);
    }
}
