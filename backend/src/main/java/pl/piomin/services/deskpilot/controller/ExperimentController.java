package pl.piomin.services.deskpilot.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
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
import pl.piomin.services.deskpilot.dto.ExperimentResponse;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.Experiment;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.ExperimentService;

/** A/B experiments on prompts and models (12m), admins only (see SecurityConfig). */
@RestController
@RequestMapping("/api/experiments")
public class ExperimentController {

    /**
     * @param provider 'ollama' (default) or a cloud provider
     * @param promptId prompt version; null = the prompt from the settings
     */
    public record VariantRequest(@Size(max = 30) String provider, @NotBlank @Size(max = 100) String model, Long promptId) {

        Experiment.Variant toVariant() {
            return new Experiment.Variant(provider, model, promptId);
        }
    }

    /** @param bPercent share of new conversations sent to variant B (1–99) */
    public record StartRequest(@NotBlank @Size(max = 100) String name,
                               @NotNull @Valid VariantRequest a,
                               @NotNull @Valid VariantRequest b,
                               @Min(1) @Max(99) int bPercent) {
    }

    private final ExperimentService experiments;
    private final AuditService auditService;

    public ExperimentController(ExperimentService experiments, AuditService auditService) {
        this.experiments = experiments;
        this.auditService = auditService;
    }

    /** All experiments, newest first, with the results of both variants. */
    @GetMapping
    public List<ExperimentResponse> list() {
        return experiments.list();
    }

    @GetMapping("/{id}")
    public ExperimentResponse get(@PathVariable String id) {
        return experiments.results(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public ExperimentResponse start(@Valid @RequestBody StartRequest request, Authentication authentication) {
        Experiment experiment = experiments.start(request.name(), request.a().toVariant(), request.b().toVariant(),
                request.bPercent(), authentication.getName());
        auditService.record(authentication.getName(), AuditAction.EXPERIMENT_STARTED, "experiment", experiment.getId(),
                experiment.getName() + ": A=" + label(experiment.variantA()) + ", B=" + label(experiment.variantB())
                        + " (" + experiment.getBPercent() + "% to B)");
        return experiments.results(experiment.getId());
    }

    @PostMapping("/{id}/stop")
    public ExperimentResponse stop(@PathVariable String id, Authentication authentication) {
        Experiment experiment = experiments.stop(id, authentication.getName());
        auditService.record(authentication.getName(), AuditAction.EXPERIMENT_STOPPED, "experiment", id, experiment.getName());
        return experiments.results(id);
    }

    private static String label(Experiment.Variant variant) {
        String model = "ollama".equals(variant.provider()) ? variant.model() : variant.provider() + ":" + variant.model();
        return variant.promptId() == null ? model : model + " + prompt #" + variant.promptId();
    }
}
