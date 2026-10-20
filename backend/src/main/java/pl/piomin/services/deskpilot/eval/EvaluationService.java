package pl.piomin.services.deskpilot.eval;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.agent.SessionService;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.guardrail.GuardrailViolationException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.Evaluation;
import pl.piomin.services.deskpilot.model.EvaluationResult;
import pl.piomin.services.deskpilot.model.EvaluationStatus;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.repository.EvaluationRepository;
import pl.piomin.services.deskpilot.repository.EvaluationResultRepository;
import pl.piomin.services.deskpilot.service.RunService;

/**
 * Runs the scenario catalogue against a model through the real agent (tools, knowledge base, guardrails) and scores
 * every answer, so models and settings can be compared on the same requests. One evaluation at a time, in the
 * background: each scenario gets its own session as user {@value #EVALUATION_USER}; actions waiting for approval are
 * checked and then abandoned, so no ticket is ever created.
 */
@Service
@Transactional(readOnly = true)
public class EvaluationService {

    static final String EVALUATION_USER = "evaluation";
    private static final Logger LOG = LoggerFactory.getLogger(EvaluationService.class);

    private final ScenarioCatalog catalog;
    private final AgentOrchestrator orchestrator;
    private final SessionService sessionService;
    private final RunService runService;
    private final AgentSettingsService settingsService;
    private final ModelCatalog modelCatalog;
    private final CloudProviderService cloudProviders;
    private final EvaluationRepository evaluations;
    private final EvaluationResultRepository results;

    public EvaluationService(ScenarioCatalog catalog, AgentOrchestrator orchestrator, SessionService sessionService,
                             RunService runService, AgentSettingsService settingsService, ModelCatalog modelCatalog,
                             CloudProviderService cloudProviders,
                             EvaluationRepository evaluations, EvaluationResultRepository results) {
        this.catalog = catalog;
        this.orchestrator = orchestrator;
        this.sessionService = sessionService;
        this.runService = runService;
        this.settingsService = settingsService;
        this.modelCatalog = modelCatalog;
        this.cloudProviders = cloudProviders;
        this.evaluations = evaluations;
        this.results = results;
    }

    public List<Scenario> scenarios() {
        return catalog.all();
    }

    /**
     * Starts an evaluation in the background with the current settings and the given model.
     * Not transactional: the evaluation row must be committed before the worker thread reads it.
     */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Evaluation start(String model, Collection<String> categories, String startedBy) {
        return start("ollama", model, categories, startedBy);
    }

    /** With a cloud provider (12s), the same scenarios compare a cloud model with the local one. */
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    public Evaluation start(String provider, String model, Collection<String> categories, String startedBy) {
        if (CloudProviderService.isCloud(provider)) {
            if (!cloudProviders.isAvailable(provider)) {
                throw new IllegalArgumentException("Provider '" + provider + "' is not enabled or has no API key");
            }
            if (cloudProviders.models(provider).stream().noneMatch(m -> m.id().equals(model) && m.supportsTools())) {
                throw new IllegalArgumentException("Model '" + model + "' is not offered by " + provider + " with tool calling");
            }
        } else {
            ModelCatalog.ModelInfo info = modelCatalog.find(model)
                    .orElseThrow(() -> new IllegalArgumentException("Model '" + model + "' is not installed in Ollama"));
            if (!info.supportsTools()) {
                throw new IllegalArgumentException("Model '" + model + "' does not support tool calling");
            }
        }
        List<Scenario> scenarios = catalog.select(categories);
        if (scenarios.isEmpty()) {
            throw new IllegalArgumentException("No scenarios in categories " + categories);
        }
        if (evaluations.existsByStatus(EvaluationStatus.RUNNING)) {
            throw new ConflictException("Another evaluation is running; wait for it or cancel it");
        }
        AgentConfig config = settingsService.current().withChat(CloudProviderService.isCloud(provider) ? provider : "ollama", model);
        Evaluation evaluation = evaluations.save(new Evaluation(config.modelLabel(), scenarios.size(), startedBy));
        Thread.ofVirtual().name("evaluation-" + evaluation.getId()).start(() -> runAll(evaluation.getId(), scenarios, config));
        return evaluation;
    }

    /** Stops after the scenario in progress. */
    @Transactional
    public Evaluation cancel(String id) {
        Evaluation evaluation = get(id);
        if (evaluations.finish(id, EvaluationStatus.CANCELLED, Instant.now(), null) == 0) {
            throw new ConflictException("Evaluation " + id + " is not running");
        }
        return evaluation;
    }

    public Evaluation get(String id) {
        return evaluations.findById(id).orElseThrow(() -> new NotFoundException("Evaluation " + id + " not found"));
    }

    public List<Evaluation> list() {
        return evaluations.findAllByOrderByStartedAtDesc();
    }

    public List<EvaluationResult> results(String evaluationId) {
        return results.findByEvaluationIdOrderByIdAsc(evaluationId);
    }

    public Map<String, List<EvaluationResult>> resultsByEvaluation(Collection<String> evaluationIds) {
        return results.findByEvaluationIdIn(evaluationIds).stream()
                .collect(Collectors.groupingBy(EvaluationResult::getEvaluationId));
    }

    void runAll(String evaluationId, List<Scenario> scenarios, AgentConfig config) {
        try {
            for (Scenario scenario : scenarios) {
                if (get(evaluationId).getStatus() != EvaluationStatus.RUNNING) {
                    LOG.info("Evaluation {} stopped before scenario {}", evaluationId, scenario.id());
                    return;
                }
                EvaluationResult result = results.save(runScenario(evaluationId, scenario, config));
                evaluations.recordResult(evaluationId, result.isPassed() ? 1 : 0);
                LOG.info("Evaluation {}: {} {} in {} ms", evaluationId, scenario.id(), result.isPassed() ? "passed" : "FAILED",
                        result.getLatencyMillis());
            }
            evaluations.finish(evaluationId, EvaluationStatus.COMPLETED, Instant.now(), null);
        } catch (RuntimeException e) {
            LOG.error("Evaluation {} failed", evaluationId, e);
            evaluations.finish(evaluationId, EvaluationStatus.FAILED, Instant.now(), e.getMessage());
        }
    }

    private EvaluationResult runScenario(String evaluationId, Scenario scenario, AgentConfig config) {
        String sessionId = sessionService.create(EVALUATION_USER).getId();
        long start = System.nanoTime();
        Outcome outcome;
        List<String> tools = List.of();
        String answer = null;
        String pendingArguments = "";
        AgentRun run = null;
        try {
            run = orchestrator.evaluate(sessionId, scenario.question(), config);
            tools = runService.steps(run.getId()).stream()
                    .filter(step -> step.getType() == StepType.TOOL_CALL)
                    .map(AgentStep::getName)
                    .toList();
            outcome = outcomeOf(run.getStatus());
            if (outcome == Outcome.APPROVAL) {
                pendingArguments = orchestrator.pendingApprovals(run.getId()).stream()
                        .map(call -> call.name() + " " + call.arguments())
                        .collect(Collectors.joining("\n"));
                answer = pendingArguments;
            } else {
                answer = run.getFinalAnswer();
            }
        } catch (GuardrailViolationException e) {
            outcome = Outcome.BLOCKED;
            answer = e.getMessage();
        } catch (RuntimeException e) {
            LOG.warn("Scenario {} failed with an error", scenario.id(), e);
            outcome = Outcome.FAILED;
            answer = e.getClass().getSimpleName() + ": " + e.getMessage();
        }
        long latencyMillis = (System.nanoTime() - start) / 1_000_000;
        if (run != null && run.getStatus() == RunStatus.AWAITING_APPROVAL) {
            run = orchestrator.abandon(run.getId(), "Evaluation scenario: the approval is not simulated");
        }
        List<String> failures = ScenarioScorer.score(scenario.expect(),
                new ScenarioScorer.Observed(outcome, tools, outcome == Outcome.APPROVAL ? null : answer, pendingArguments));
        return new EvaluationResult(evaluationId, scenario.id(), scenario.category(), scenario.question(), failures.isEmpty(),
                scenario.expect().outcome().name(), outcome.name(), String.join(",", tools), String.join("\n", failures),
                answer, run == null ? null : run.getId(), latencyMillis, run == null ? 0 : run.getIterations(),
                run == null ? 0 : run.getPromptTokens() + run.getCompletionTokens());
    }

    private static Outcome outcomeOf(RunStatus status) {
        return switch (status) {
            case COMPLETED -> Outcome.ANSWERED;
            case AWAITING_APPROVAL -> Outcome.APPROVAL;
            case ESCALATED -> Outcome.ESCALATED;
            case FAILED, RUNNING -> Outcome.FAILED;
        };
    }
}
