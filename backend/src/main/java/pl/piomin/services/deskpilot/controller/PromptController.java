package pl.piomin.services.deskpilot.controller;

import java.time.Instant;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.PromptVersion;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.PromptVersionService;

/** Named versions of the system prompt (12m), admins only (see SecurityConfig). */
@RestController
@RequestMapping("/api/prompts")
public class PromptController {

    public record CreateRequest(@NotBlank @Size(max = 100) String name, @NotBlank @Size(max = 8000) String content) {
    }

    public record PromptVersionResponse(Long id, String name, String content, String createdBy, Instant createdAt) {

        static PromptVersionResponse of(PromptVersion version) {
            return new PromptVersionResponse(version.getId(), version.getName(), version.getContent(),
                    version.getCreatedBy(), version.getCreatedAt());
        }
    }

    private final PromptVersionService prompts;
    private final AuditService auditService;

    public PromptController(PromptVersionService prompts, AuditService auditService) {
        this.prompts = prompts;
        this.auditService = auditService;
    }

    @GetMapping
    public List<PromptVersionResponse> list() {
        return prompts.list().stream().map(PromptVersionResponse::of).toList();
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public PromptVersionResponse create(@Valid @RequestBody CreateRequest request, Authentication authentication) {
        PromptVersion version = prompts.create(request.name(), request.content(), authentication.getName());
        auditService.record(authentication.getName(), AuditAction.PROMPT_VERSION_SAVED, "prompt", String.valueOf(version.getId()),
                version.getName());
        return PromptVersionResponse.of(version);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable Long id, Authentication authentication) {
        PromptVersion version = prompts.delete(id);
        auditService.record(authentication.getName(), AuditAction.PROMPT_VERSION_DELETED, "prompt", String.valueOf(id),
                version.getName());
    }
}
