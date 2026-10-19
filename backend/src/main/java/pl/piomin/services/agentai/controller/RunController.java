package pl.piomin.services.agentai.controller;

import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.agentai.agent.AgentOrchestrator;
import pl.piomin.services.agentai.dto.ApprovalRequest;
import pl.piomin.services.agentai.dto.RunResponse;
import pl.piomin.services.agentai.service.RunService;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final AgentOrchestrator orchestrator;
    private final RunResponseMapper mapper;

    public RunController(RunService runService, AgentOrchestrator orchestrator, RunResponseMapper mapper) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.mapper = mapper;
    }

    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable String runId) {
        return mapper.toResponse(runService.get(runId));
    }

    /** Human-in-the-loop decision on the tool calls a paused run is waiting for; resumes the run. */
    @PostMapping("/{runId}/approval")
    public RunResponse decide(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request) {
        return mapper.toResponse(orchestrator.decide(runId,
                new AgentOrchestrator.ApprovalDecision(request.approved(), request.comment())));
    }
}
