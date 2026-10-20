package pl.piomin.services.deskpilot.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.agent.specialist.SpecialistCatalog;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.dto.AgentSettingsDto;
import pl.piomin.services.deskpilot.dto.SpecialistInfo;
import pl.piomin.services.deskpilot.dto.ToolInfo;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.service.AuditService;

/** Runtime configuration of the agent: model, sampling, RAG, tools. Changes apply to the next run. */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private final AgentSettingsService settingsService;
    private final ModelCatalog modelCatalog;
    private final ToolRegistry toolRegistry;
    private final AuditService auditService;
    private final SpecialistCatalog specialists;

    public SettingsController(AgentSettingsService settingsService, ModelCatalog modelCatalog, ToolRegistry toolRegistry,
                              AuditService auditService, SpecialistCatalog specialists) {
        this.settingsService = settingsService;
        this.modelCatalog = modelCatalog;
        this.toolRegistry = toolRegistry;
        this.auditService = auditService;
        this.specialists = specialists;
    }

    @GetMapping("/settings")
    public AgentSettingsDto settings() {
        return AgentSettingsDto.of(settingsService.get());
    }

    @PutMapping("/settings")
    public AgentSettingsDto update(@Valid @RequestBody AgentSettingsDto request, Authentication authentication) {
        AgentSettingsDto before = AgentSettingsDto.of(settingsService.get());
        AgentSettingsDto after = AgentSettingsDto.of(settingsService.update(request.toValues()));
        String changes = AuditService.diff(before, after, "updatedAt");
        if (!changes.isEmpty()) {
            auditService.record(authentication.getName(), AuditAction.SETTINGS_CHANGED, "settings", null, changes);
        }
        return after;
    }

    /** Restores the defaults from application.yml. */
    @DeleteMapping("/settings")
    public AgentSettingsDto reset(Authentication authentication) {
        AgentSettingsDto before = AgentSettingsDto.of(settingsService.get());
        AgentSettingsDto after = AgentSettingsDto.of(settingsService.reset());
        auditService.record(authentication.getName(), AuditAction.SETTINGS_RESET, "settings", null,
                AuditService.diff(before, after, "updatedAt"));
        return after;
    }

    /** Models installed in Ollama, with their capabilities. */
    @GetMapping("/models")
    public List<ModelCatalog.ModelInfo> models() {
        return modelCatalog.list();
    }

    /** Specialist agents the supervisor routes messages to when the Settings toggle is on (12f). */
    @GetMapping("/specialists")
    public List<SpecialistInfo> specialists() {
        return specialists.all().stream()
                .map(s -> new SpecialistInfo(s.id(), s.name(), s.description(), s.keywords(),
                        toolRegistry.names().stream().filter(s::allowsTool).sorted().toList(), s.examples().size()))
                .toList();
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return toolRegistry.all().stream()
                .map(tool -> tool.getToolDefinition())
                .map(def -> new ToolInfo(def.name(), def.description(),
                        toolRegistry.defaultApprovalTools().contains(def.name()) || toolRegistry.isExternal(def.name()),
                        toolRegistry.isExternal(def.name())))
                .toList();
    }
}
