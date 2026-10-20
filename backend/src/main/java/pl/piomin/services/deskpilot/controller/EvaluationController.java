package pl.piomin.services.deskpilot.controller;

import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.EvaluationResponse;
import pl.piomin.services.deskpilot.eval.EvaluationService;
import pl.piomin.services.deskpilot.eval.Scenario;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.Evaluation;
import pl.piomin.services.deskpilot.model.EvaluationResult;
import pl.piomin.services.deskpilot.service.AuditService;

/** Runs the scenario catalogue against a model and reports the scores (admins only, see SecurityConfig). */
@RestController
@RequestMapping("/api/evaluations")
public class EvaluationController {

    /**
     * @param model      installed Ollama model with tool calling
     * @param categories scenario categories to run; all when empty
     */
    public record StartRequest(@NotBlank @Size(max = 100) String model, List<@NotBlank String> categories,
                               @Size(max = 30) String provider) {

        public StartRequest(String model, List<String> categories) {
            this(model, categories, null);
        }
    }

    private final EvaluationService evaluationService;
    private final AuditService auditService;

    public EvaluationController(EvaluationService evaluationService, AuditService auditService) {
        this.evaluationService = evaluationService;
        this.auditService = auditService;
    }

    @GetMapping("/scenarios")
    public List<Scenario> scenarios() {
        return evaluationService.scenarios();
    }

    /** Starts in the background; poll {@code GET /api/evaluations/{id}} for progress. */
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public EvaluationResponse start(@Valid @RequestBody StartRequest request, Authentication authentication) {
        Evaluation evaluation = evaluationService.start(request.provider() == null ? "ollama" : request.provider(),
                request.model(), request.categories(), authentication.getName());
        auditService.record(authentication.getName(), AuditAction.EVALUATION_STARTED, "evaluation", evaluation.getId(),
                "model " + evaluation.getModel() + ", " + evaluation.getScenarioCount() + " scenario(s)"
                        + (request.categories() == null || request.categories().isEmpty() ? "" : ", categories " + request.categories()));
        return EvaluationResponse.of(evaluation, List.of(), false);
    }

    @GetMapping
    public List<EvaluationResponse> list() {
        List<Evaluation> evaluations = evaluationService.list();
        Map<String, List<EvaluationResult>> results =
                evaluationService.resultsByEvaluation(evaluations.stream().map(Evaluation::getId).toList());
        return evaluations.stream()
                .map(e -> EvaluationResponse.of(e, results.getOrDefault(e.getId(), List.of()), false))
                .toList();
    }

    @GetMapping("/{id}")
    public EvaluationResponse get(@PathVariable String id) {
        return EvaluationResponse.of(evaluationService.get(id), evaluationService.results(id), true);
    }

    @PostMapping("/{id}/cancel")
    public EvaluationResponse cancel(@PathVariable String id, Authentication authentication) {
        evaluationService.cancel(id);
        auditService.record(authentication.getName(), AuditAction.EVALUATION_CANCELLED, "evaluation", id, null);
        return get(id);
    }
}
