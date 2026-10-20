package pl.piomin.services.deskpilot.controller;

import java.util.ArrayList;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.cloud.CloudProviderService;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.service.AuditService;

/**
 * Cloud model providers (12s), admins only (see SecurityConfig). API keys can be written but are never returned:
 * responses only say where the key comes from and show its last characters.
 */
@RestController
@RequestMapping("/api/providers")
public class ProviderController {

    /**
     * @param enabled   turn the provider on or off; unchanged when null
     * @param apiKey    new key to store (encrypted); unchanged when null or blank
     * @param removeKey delete the stored key
     */
    public record UpdateRequest(Boolean enabled, @Size(max = 500) String apiKey, Boolean removeKey) {

        boolean removesKey() {
            return Boolean.TRUE.equals(removeKey);
        }
    }

    private final CloudProviderService providers;
    private final AuditService auditService;

    public ProviderController(CloudProviderService providers, AuditService auditService) {
        this.providers = providers;
        this.auditService = auditService;
    }

    @GetMapping
    public List<CloudProviderService.Status> list() {
        return providers.list();
    }

    @PatchMapping("/{id}")
    public CloudProviderService.Status update(@PathVariable String id, @Valid @RequestBody UpdateRequest request,
                                              Authentication authentication) {
        CloudProviderService.Status before = providers.get(id);
        CloudProviderService.Status after = providers.update(id, request.enabled(), request.apiKey(), request.removesKey(),
                authentication.getName());
        String actor = authentication.getName();
        if (request.removesKey()) {
            auditService.record(actor, AuditAction.PROVIDER_KEY_REMOVED, "provider", id, null);
        } else if (request.apiKey() != null && !request.apiKey().isBlank()) {
            auditService.record(actor, AuditAction.PROVIDER_KEY_SET, "provider", id, "key ending in " + after.keyHint());
        }
        if (request.enabled() != null && request.enabled() != before.enabled()) {
            auditService.record(actor, AuditAction.PROVIDER_UPDATED, "provider", id, request.enabled() ? "enabled" : "disabled");
        }
        return after;
    }

    /** Checks the key by listing the provider's models. */
    @PostMapping("/{id}/test")
    public CloudProviderService.TestResult test(@PathVariable String id) {
        return providers.test(id);
    }

    @GetMapping("/{id}/models")
    public List<CloudProviderService.CloudModel> models(@PathVariable String id) {
        return new ArrayList<>(providers.models(id));
    }
}
