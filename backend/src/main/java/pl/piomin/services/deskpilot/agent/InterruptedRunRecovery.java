package pl.piomin.services.deskpilot.agent;

import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.repository.AgentSessionRepository;
import pl.piomin.services.deskpilot.repository.EvaluationRepository;
import pl.piomin.services.deskpilot.repository.PendingTurnRepository;
import pl.piomin.services.deskpilot.service.RunService;

/**
 * On startup, closes runs that were cut off by the previous shutdown: a run still {@code RUNNING} lost its
 * in-flight model call, and one awaiting approval without a saved turn cannot be resumed. Runs paused with a
 * saved turn stay open for approval. Assumes a single backend instance.
 */
@Component
class InterruptedRunRecovery implements ApplicationRunner {

    private static final Logger LOG = LoggerFactory.getLogger(InterruptedRunRecovery.class);

    static final String INTERRUPTED = "Interrupted by a backend restart. Please ask again.";

    private final RunService runService;
    private final PendingTurnRepository pendingTurnRepository;
    private final AgentSessionRepository sessionRepository;
    private final EvaluationRepository evaluationRepository;

    InterruptedRunRecovery(RunService runService, PendingTurnRepository pendingTurnRepository,
                           AgentSessionRepository sessionRepository, EvaluationRepository evaluationRepository) {
        this.runService = runService;
        this.pendingTurnRepository = pendingTurnRepository;
        this.sessionRepository = sessionRepository;
        this.evaluationRepository = evaluationRepository;
    }

    @Override
    public void run(ApplicationArguments args) {
        sessionRepository.releaseAll();
        evaluationRepository.failRunning(Instant.now(), "Interrupted by a backend restart");
        int failed = 0;
        for (AgentRun run : runService.findUnfinished()) {
            boolean resumable = run.getStatus() == RunStatus.AWAITING_APPROVAL && pendingTurnRepository.existsById(run.getId());
            if (!resumable) {
                runService.addStep(run.getId(), StepType.ERROR, "Interrupted", INTERRUPTED, true, 0);
                runService.finish(run.getId(), RunStatus.FAILED, INTERRUPTED, 0);
                failed++;
            }
        }
        if (failed > 0) {
            LOG.warn("Marked {} run(s) interrupted by the previous shutdown as FAILED", failed);
        }
    }
}
