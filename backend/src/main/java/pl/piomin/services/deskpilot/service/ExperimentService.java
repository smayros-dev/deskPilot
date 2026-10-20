package pl.piomin.services.deskpilot.service;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.AgentConfig;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.dto.ExperimentResponse;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.exception.NotFoundException;
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
 * by a hash of its id, so all its messages use the same variant; results compare success, latency and feedback.
 */
@Service
@Transactional(readOnly = true)
public class ExperimentService {

    public static final String A = "A";
    public static final String B = "B";

    /** Where a chat run goes: the experiment, the variant and the settings that variant runs with. */
    public record Assignment(String experimentId, String variant, AgentConfig config) {
    }

    private final ExperimentRepository repository;
    private final PromptVersionRepository promptRepository;
    private final AgentRunRepository runRepository;
    private final RunFeedbackRepository feedbackRepository;
    private final AgentSettingsService settingsService;

    public ExperimentService(ExperimentRepository repository, PromptVersionRepository promptRepository,
                             AgentRunRepository runRepository, RunFeedbackRepository feedbackRepository,
                             AgentSettingsService settingsService) {
        this.repository = repository;
        this.promptRepository = promptRepository;
        this.runRepository = runRepository;
        this.feedbackRepository = feedbackRepository;
        this.settingsService = settingsService;
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
        return new ExperimentResponse(experiment.getId(), experiment.getName(), experiment.getStatus(), experiment.getBPercent(),
                experiment.getStartedBy(), experiment.getStartedAt(), experiment.getStoppedBy(), experiment.getStoppedAt(),
                List.of(variant(experiment, A, experiment.variantA(), totals.get(A), feedback.get(A)),
                        variant(experiment, B, experiment.variantB(), totals.get(B), feedback.get(B))));
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
