package pl.piomin.services.deskpilot.controller;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.specialist.Specialist;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistCatalog;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistRouter;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.dto.SpecialistInfo;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.SpecialistService;

/**
 * Specialist agents (12f, 13g). Everyone can list them; admins (see SecurityConfig) create, edit, delete and restore
 * them and try how a message would be routed.
 */
@RestController
@RequestMapping("/api/specialists")
public class SpecialistController {

    public record SpecialistRequest(@Size(max = 40) String id,
                                    @NotBlank @Size(max = 100) String name,
                                    @NotBlank @Size(max = 500) String description,
                                    List<String> keywords,
                                    List<String> tools,
                                    @NotBlank @Size(max = 4000) String prompt,
                                    List<Specialist.Example> examples,
                                    @NotNull Boolean enabled,
                                    Integer sortOrder) {

        SpecialistService.Values values() {
            return new SpecialistService.Values(name, description, keywords, tools, prompt, examples, enabled,
                    sortOrder == null ? 100 : sortOrder);
        }
    }

    public record RoutePreviewRequest(@NotBlank @Size(max = 4000) String message) {
    }

    /**
     * @param confidence   keyword share or similarity of the choice; null when chosen otherwise
     * @param keywords     matched keywords per specialist
     * @param similarities closest description or example per specialist (empty when the embedding model is down)
     */
    public record RoutePreview(String specialist, String name, String method, Double confidence, String detail,
                               Map<String, List<String>> keywords, Map<String, Double> similarities) {
    }

    /** Audited fields of a specialist, compared before and after an edit (public: read by reflection). */
    public record Audited(String name, String description, List<String> keywords, List<String> tools, String prompt,
                           List<Specialist.Example> examples, boolean enabled, int sortOrder) {

        static Audited of(SpecialistService.Definition d) {
            Specialist s = d.specialist();
            return new Audited(s.name(), s.description(), s.keywords(), s.tools(), s.prompt(), s.examples(),
                    d.enabled(), d.sortOrder());
        }
    }

    private final SpecialistService service;
    private final SpecialistCatalog catalog;
    private final SpecialistRouter router;
    private final AgentSettingsService settingsService;
    private final ToolRegistry toolRegistry;
    private final AuditService auditService;

    public SpecialistController(SpecialistService service, SpecialistCatalog catalog, SpecialistRouter router,
                                AgentSettingsService settingsService, ToolRegistry toolRegistry, AuditService auditService) {
        this.service = service;
        this.catalog = catalog;
        this.router = router;
        this.settingsService = settingsService;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
    }

    /** The enabled specialists and the general agent; admins also get disabled ones and everything they edit. */
    @GetMapping
    public List<SpecialistInfo> list(Authentication authentication) {
        if (!CurrentUser.of(authentication).isAdmin()) {
            return catalog.all().stream().map(s -> SpecialistInfo.summary(s, tools(s), true)).toList();
        }
        List<SpecialistInfo> all = new ArrayList<>(service.list().stream().map(this::full).toList());
        all.add(SpecialistInfo.full(Specialist.GENERAL, tools(Specialist.GENERAL), true, null, null, null));
        return all;
    }

    @GetMapping("/{id}")
    public SpecialistInfo get(@PathVariable String id) {
        return full(service.get(id));
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public SpecialistInfo create(@Valid @RequestBody SpecialistRequest request, Authentication authentication) {
        SpecialistService.Definition created = service.create(request.id(), request.values(), authentication.getName());
        auditService.record(authentication.getName(), AuditAction.SPECIALIST_CREATED, "specialist",
                created.specialist().id(), created.specialist().name());
        return full(created);
    }

    @PutMapping("/{id}")
    public SpecialistInfo update(@PathVariable String id, @Valid @RequestBody SpecialistRequest request,
                                 Authentication authentication) {
        Audited before = Audited.of(service.get(id));
        SpecialistService.Definition updated = service.update(id, request.values(), authentication.getName());
        String changes = AuditService.diff(before, Audited.of(updated));
        if (!changes.isEmpty()) {
            auditService.record(authentication.getName(), AuditAction.SPECIALIST_UPDATED, "specialist", id, changes);
        }
        return full(updated);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable String id, Authentication authentication) {
        SpecialistService.Definition deleted = service.delete(id);
        auditService.record(authentication.getName(), AuditAction.SPECIALIST_DELETED, "specialist", id,
                deleted.specialist().name());
    }

    /** Replaces every specialist with the built-in ones. */
    @PostMapping("/restore-defaults")
    public List<SpecialistInfo> restoreDefaults(Authentication authentication) {
        List<SpecialistService.Definition> restored = service.restoreDefaults(authentication.getName());
        auditService.record(authentication.getName(), AuditAction.SPECIALISTS_RESTORED, "specialist", null,
                restored.size() + " built-in specialists");
        return list(authentication);
    }

    /** Where a message would be routed now, without running the agent (model only with model fallback on). */
    @PostMapping("/route-preview")
    public RoutePreview routePreview(@Valid @RequestBody RoutePreviewRequest request) {
        SpecialistRouter.Preview preview = router.preview(request.message(), settingsService.current());
        SpecialistRouter.Routing routing = preview.routing();
        return new RoutePreview(routing.specialist().id(), routing.specialist().name(),
                routing.method().name().toLowerCase(Locale.ROOT), routing.confidence(), routing.detail(),
                preview.keywords(), preview.similarities());
    }

    private SpecialistInfo full(SpecialistService.Definition definition) {
        Specialist specialist = definition.specialist();
        return SpecialistInfo.full(specialist, tools(specialist), definition.enabled(), definition.sortOrder(),
                definition.updatedBy(), definition.updatedAt());
    }

    private List<String> tools(Specialist specialist) {
        return toolRegistry.names().stream().filter(specialist::allowsTool).sorted().toList();
    }
}
