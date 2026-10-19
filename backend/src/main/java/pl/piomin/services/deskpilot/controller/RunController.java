package pl.piomin.services.deskpilot.controller;

import java.util.List;

import jakarta.validation.Valid;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.dto.ApprovalRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.RunService;

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

    /** Runs waiting for a human decision, oldest first (reviewers and admins only, see SecurityConfig). */
    @GetMapping
    public List<RunResponse> awaitingApproval() {
        return runService.findAwaitingApproval().stream().map(mapper::toResponse).toList();
    }

    /** A run of the caller; reviewers and admins can open anyone's. */
    @GetMapping("/{runId}")
    public RunResponse get(@PathVariable String runId, Authentication authentication) {
        AgentRun run = runService.get(runId);
        if (!CurrentUser.of(authentication).canSee(run.getUserId())) {
            throw new NotFoundException("Run " + runId + " not found");
        }
        return mapper.toResponse(run);
    }

    /** Human-in-the-loop decision on the tool calls a paused run is waiting for; resumes the run (reviewers only). */
    @PostMapping("/{runId}/approval")
    public RunResponse decide(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request,
                              Authentication authentication) {
        return mapper.toResponse(orchestrator.decide(runId,
                new AgentOrchestrator.ApprovalDecision(request.approved(), request.comment(), authentication.getName())));
    }
}
