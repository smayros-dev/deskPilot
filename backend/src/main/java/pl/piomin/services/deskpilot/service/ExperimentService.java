package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.context.event.EventListener;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.cloud.ProviderUnavailable;
import pl.piomin.services.deskpilot.config.ExperimentProperties;
import pl.piomin.services.deskpilot.dto.ExperimentResponse;
import pl.piomin.services.deskpilot.dto.ExperimentResponse.Significance;
import pl.piomin.services.deskpilot.dto.ExperimentResponse.Verdict;
import pl.piomin.services.deskpilot.events.ExperimentAutoStopped;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.ModelProviderException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.Experiment;
import pl.piomin.services.deskpilot.model.ExperimentStatus;
import pl.piomin.services.deskpilot.model.PromptVersion;
import pl.piomin.services.deskpilot.repository.AgentRunRepository;
import pl.piomin.services.deskpilot.repository.AgentRunRepository.VariantTotals;
import pl.piomin.services.deskpilot.repository.ExperimentRepository;
import pl.piomin.services.deskpilot.repository.PromptVersionRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository;
import pl.piomin.services.deskpilot.repository.RunFeedbackRepository.VariantFeedback;

/**
 * A/B experiments on prompts and models (12m). While an experiment runs, each conversation is assigned to a variant
 * by a hash of its id, so all its messages use the same variant; results compare success, latency and feedback, with
 * a significance test. An experiment whose variant can no longer run (its cloud provider was disabled or keeps failing)
 * is stopped automatically, so its conversations go back to the normal settings.
 */
@Service
@Transactional(readOnly = true)
public class ExperimentService {

    public static final String A = "A";
    public static final String B = "B";

    private static final Logger LOG = LoggerFactory.getLogger(ExperimentService.class);
    private static final double ALPHA = 0.05;

    /** Where a chat run goes: the experiment, the variant and the settings that variant runs with. */
    public record Assignment(String experimentId, String variant, AgentConfig config) {
    }

    private final ExperimentRepository repository;
    private final PromptVersionRepository promptRepository;
    private final AgentRunRepository runRepository;
    private final RunFeedbackRepository feedbackRepository;
    private final AgentSettingsService settingsService;
    private final AuditService auditService;
    private final ApplicationEventPublisher events;
    private final ExperimentProperties properties;
    /** Consecutive provider errors per "experiment:variant"; any other outcome resets the count. */
    private final Map<String, AtomicInteger> providerFailures = new ConcurrentHashMap<>();

    public ExperimentService(ExperimentRepository repository, PromptVersionRepository promptRepository,
                             AgentRunRepository runRepository, RunFeedbackRepository feedbackRepository,
                             AgentSettingsService settingsService, AuditService auditService,
                             ApplicationEventPublisher events, ExperimentProperties properties) {
        this.repository = repository;
        this.promptRepository = promptRepository;
        this.runRepository = runRepository;
        this.feedbackRepository = feedbackRepository;
        this.settingsService = settingsService;
        this.auditService = auditService;
        this.events = events;
        this.properties = properties;
    }

    /**
     * Starts an experiment; both models are checked like a settings change.
     *
     * @throws ConflictException        if another experiment is running
     * @throws IllegalArgumentException if a model cannot be used, a prompt version is unknown or both variants are equal
     */
    @Transactional
    public Experiment start(String name, Experiment.Variant a, Experiment.Variant b, int bPercent, String actor) {
        if (repository.findFirstByStatus(ExperimentStatus.RUNNING).isPresent()) {
            throw new ConflictException("Another experiment is running; stop it first");
        }
        Experiment.Variant first = normalize(a);
        Experiment.Variant second = normalize(b);
        if (first.equals(second)) {
            throw new IllegalArgumentException("Variants A and B are identical; change the model or the prompt of one of them");
        }
        for (Experiment.Variant variant : List.of(first, second)) {
            settingsService.validateChatModel(variant.provider(), variant.model());
            if (variant.promptId() != null && !promptRepository.existsById(variant.promptId())) {
                throw new IllegalArgumentException("Prompt version " + variant.promptId() + " does not exist");
            }
        }
        try {
            return repository.saveAndFlush(new Experiment(name.strip(), first, second, bPercent, actor));
        } catch (DataIntegrityViolationException e) {
            // Two admins started one at the same moment: the partial unique index keeps a single running experiment.
            throw new ConflictException("Another experiment is running; stop it first");
        }
    }

    /** @throws ConflictException if the experiment is already stopped */
    @Transactional
    public Experiment stop(String id, String actor) {
        Experiment experiment = get(id);
        if (!experiment.isRunning()) {
            throw new ConflictException("Experiment " + id + " is already stopped");
        }
        experiment.stop(actor);
        return experiment;
    }

    /**
     * A cloud provider was disabled or lost its key: a running experiment with a variant on it is stopped in the same
     * transaction, so no conversation is assigned to a variant that cannot answer.
     */
    @EventListener
    @Transactional
    public void onProviderUnavailable(ProviderUnavailable event) {
        repository.findFirstByStatus(ExperimentStatus.RUNNING)
                .filter(experiment -> experiment.uses(event.providerId()))
                .ifPresent(experiment -> stopAutomatically(experiment,
                        event.label() + " was disabled or lost its API key"));
    }

    /**
     * Counts provider errors of a finished experiment run: after {@code agent.experiments.max-provider-failures}
     * consecutive runs of one variant failing with a {@link ModelProviderException}, the experiment is stopped.
     *
     * @param failure why the run failed; null when it finished without an error
     */
    @Transactional
    public void recordOutcome(AgentRun run, Throwable failure) {
        if (run.getExperimentId() == null || run.getVariant() == null) {
            return;
        }
        String key = run.getExperimentId() + ":" + run.getVariant();
        if (!isProviderError(failure)) {
            providerFailures.remove(key);
            return;
        }
        int failures = providerFailures.computeIfAbsent(key, k -> new AtomicInteger()).incrementAndGet();
        if (failures < properties.maxProviderFailures()) {
            return;
        }
        providerFailures.remove(key);
        repository.findById(run.getExperimentId())
                .filter(Experiment::isRunning)
                .ifPresent(experiment -> stopAutomatically(experiment, "Variant " + run.getVariant() + " failed "
                        + failures + " times in a row with a model-provider error: " + failure.getMessage()));
    }

    /** A {@link ModelProviderException} anywhere in the cause chain (it may be wrapped by the run's error handling). */
    private static boolean isProviderError(Throwable failure) {
        for (Throwable t = failure; t != null; t = t.getCause() == t ? null : t.getCause()) {
            if (t instanceof ModelProviderException) {
                return true;
            }
        }
        return false;
    }

    private void stopAutomatically(Experiment experiment, String reason) {
        experiment.stop(AuditService.SYSTEM, reason);
        repository.saveAndFlush(experiment);
        auditService.record(AuditService.SYSTEM, AuditAction.EXPERIMENT_STOPPED, "experiment", experiment.getId(),
                experiment.getName() + ": " + reason);
        events.publishEvent(new ExperimentAutoStopped(experiment.getId(), experiment.getName(), experiment.getStopReason()));
        LOG.warn("Experiment {} stopped automatically: {}", experiment.getId(), reason);
    }

    public Experiment get(String id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Experiment " + id + " not found"));
    }

    public List<ExperimentResponse> list() {
        return repository.findAllByOrderByStartedAtDesc().stream().map(this::results).toList();
    }

    public ExperimentResponse results(String id) {
        return results(get(id));
    }

    /**
     * The variant for a new chat run in this conversation, if an experiment is running: its provider, model and prompt
     * replace those of the current settings. The assignment depends only on the experiment and the conversation.
     */
    public Optional<Assignment> assign(String sessionId, AgentConfig settings) {
        return repository.findFirstByStatus(ExperimentStatus.RUNNING).map(experiment -> {
            String variant = Math.floorMod((experiment.getId() + ":" + sessionId).hashCode(), 100) < experiment.getBPercent() ? B : A;
            Experiment.Variant arm = A.equals(variant) ? experiment.variantA() : experiment.variantB();
            AgentConfig config = settings.withChat(arm.provider(), arm.model());
            if (arm.promptId() != null) {
                config = config.withSystemPrompt(promptRepository.findById(arm.promptId())
                        .map(PromptVersion::getContent)
                        .orElse(settings.systemPrompt()));
            }
            return new Assignment(experiment.getId(), variant, config);
        });
    }

    private ExperimentResponse results(Experiment experiment) {
        Map<String, VariantTotals> totals = runRepository.totalsByVariant(experiment.getId()).stream()
                .filter(t -> t.getVariant() != null)
                .collect(Collectors.toMap(VariantTotals::getVariant, Function.identity()));
        Map<String, VariantFeedback> feedback = feedbackRepository.totalsByVariant(experiment.getId()).stream()
                .filter(f -> f.getVariant() != null)
                .collect(Collectors.toMap(VariantFeedback::getVariant, Function.identity()));
        VariantTotals totalsA = totals.get(A);
        VariantTotals totalsB = totals.get(B);
        VariantFeedback feedbackA = feedback.get(A);
        VariantFeedback feedbackB = feedback.get(B);
        ExperimentResponse.Comparison comparison = new ExperimentResponse.Comparison(properties.minSample(),
                significance(totalsA == null ? 0 : totalsA.getCompleted(), totalsA == null ? 0 : totalsA.getFinished(),
                        totalsB == null ? 0 : totalsB.getCompleted(), totalsB == null ? 0 : totalsB.getFinished()),
                significance(feedbackA == null ? 0 : feedbackA.getUp(), feedbackA == null ? 0 : feedbackA.getUp() + feedbackA.getDown(),
                        feedbackB == null ? 0 : feedbackB.getUp(), feedbackB == null ? 0 : feedbackB.getUp() + feedbackB.getDown()));
        return new ExperimentResponse(experiment.getId(), experiment.getName(), experiment.getStatus(), experiment.getBPercent(),
                experiment.getStartedBy(), experiment.getStartedAt(), experiment.getStoppedBy(), experiment.getStoppedAt(),
                experiment.getStopReason(),
                List.of(variant(experiment, A, experiment.variantA(), totalsA, feedbackA),
                        variant(experiment, B, experiment.variantB(), totalsB, feedbackB)),
                comparison);
    }

    /** B against A for one rate: no verdict below the minimum sample, else a two-sided test at the 5 % level. */
    Significance significance(long successesA, long totalA, long successesB, long totalB) {
        double rateA = ratio(successesA, totalA);
        double rateB = ratio(successesB, totalB);
        if (totalA < properties.minSample() || totalB < properties.minSample()) {
            return new Significance(totalA, totalB, rateA, rateB, null, null, Verdict.NOT_ENOUGH_DATA);
        }
        ProportionTest.Result test = ProportionTest.compare(successesA, totalA, successesB, totalB);
        double pValue = Math.round(test.pValue() * 10_000) / 10_000.0;
        Verdict verdict = test.pValue() >= ALPHA ? Verdict.NO_SIGNIFICANT_DIFFERENCE
                : (double) successesB / totalB > (double) successesA / totalA ? Verdict.B_BETTER : Verdict.B_WORSE;
        return new Significance(totalA, totalB, rateA, rateB, test.test(), pValue, verdict);
    }

    private ExperimentResponse.VariantResult variant(Experiment experiment, String name, Experiment.Variant arm,
                                                     VariantTotals totals, VariantFeedback feedback) {
        String promptName = arm.promptId() == null ? null
                : promptRepository.findById(arm.promptId()).map(PromptVersion::getName).orElse(null);
        long runs = totals == null ? 0 : totals.getRuns();
        long finished = totals == null ? 0 : totals.getFinished();
        long up = feedback == null ? 0 : feedback.getUp();
        long down = feedback == null ? 0 : feedback.getDown();
        return new ExperimentResponse.VariantResult(name, arm.provider(), arm.model(), arm.promptId(), promptName,
                runs, finished,
                ratio(totals == null ? 0 : totals.getCompleted(), finished),
                ratio(totals == null ? 0 : totals.getEscalated(), finished),
                average(totals == null ? 0 : totals.getActiveMillis(), finished),
                MetricsService.percentile(runRepository.finishedLatencies(experiment.getId(), name), 0.95),
                average(totals == null ? 0 : totals.getTokens(), finished),
                up, down, ratio(up, up + down));
    }

    private static Experiment.Variant normalize(Experiment.Variant variant) {
        String provider = variant.provider() == null || variant.provider().isBlank() ? "ollama" : variant.provider().strip();
        String model = Objects.requireNonNull(variant.model(), "model").strip();
        return new Experiment.Variant(provider, model, variant.promptId());
    }

    private static double ratio(long part, long whole) {
        return whole == 0 ? 0 : Math.round(1000.0 * part / whole) / 1000.0;
    }

    private static double average(long total, long count) {
        return count == 0 ? 0 : Math.round(10.0 * total / count) / 10.0;
    }
}
