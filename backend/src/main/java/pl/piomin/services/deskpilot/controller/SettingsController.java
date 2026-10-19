package pl.piomin.services.deskpilot.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.agent.AgentSettingsService;
import pl.piomin.services.deskpilot.agent.ModelCatalog;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.dto.AgentSettingsDto;
import pl.piomin.services.deskpilot.dto.ToolInfo;

/** Runtime configuration of the agent: model, sampling, RAG, tools. Changes apply to the next run. */
@RestController
@RequestMapping("/api")
public class SettingsController {

    private final AgentSettingsService settingsService;
    private final ModelCatalog modelCatalog;
    private final ToolRegistry toolRegistry;

    public SettingsController(AgentSettingsService settingsService, ModelCatalog modelCatalog, ToolRegistry toolRegistry) {
        this.settingsService = settingsService;
        this.modelCatalog = modelCatalog;
        this.toolRegistry = toolRegistry;
    }

    @GetMapping("/settings")
    public AgentSettingsDto settings() {
        return AgentSettingsDto.of(settingsService.get());
    }

    @PutMapping("/settings")
    public AgentSettingsDto update(@Valid @RequestBody AgentSettingsDto request) {
        return AgentSettingsDto.of(settingsService.update(request.toValues()));
    }

    /** Restores the defaults from application.yml. */
    @DeleteMapping("/settings")
    public AgentSettingsDto reset() {
        return AgentSettingsDto.of(settingsService.reset());
    }

    /** Models installed in Ollama, with their capabilities. */
    @GetMapping("/models")
    public List<ModelCatalog.ModelInfo> models() {
        return modelCatalog.list();
    }

    @GetMapping("/tools")
    public List<ToolInfo> tools() {
        return toolRegistry.all().stream()
                .map(tool -> tool.getToolDefinition())
                .map(def -> new ToolInfo(def.name(), def.description(),
                        toolRegistry.defaultApprovalTools().contains(def.name())))
                .toList();
    }
}
