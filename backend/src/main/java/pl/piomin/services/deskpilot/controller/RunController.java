package pl.piomin.services.deskpilot.controller;

import java.util.List;
import java.util.stream.Collectors;

import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
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
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.dto.ApprovalRequest;
import pl.piomin.services.deskpilot.dto.FeedbackRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.exception.GlobalExceptionHandler;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.FeedbackService;
import pl.piomin.services.deskpilot.service.RunService;

@RestController
@RequestMapping("/api/runs")
public class RunController {

    private final RunService runService;
    private final AgentOrchestrator orchestrator;
    private final RunResponseMapper mapper;
    private final GlobalExceptionHandler errors;
    private final AuditService auditService;
    private final FeedbackService feedbackService;

    public RunController(RunService runService, AgentOrchestrator orchestrator, RunResponseMapper mapper,
                         GlobalExceptionHandler errors, FeedbackService feedbackService,
                         AuditService auditService) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.mapper = mapper;
        this.errors = errors;
        this.auditService = auditService;
        this.feedbackService = feedbackService;
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

    /** The caller rates the answer of one of their finished runs; rating again replaces the earlier rating. */
    @PutMapping("/{runId}/feedback")
    public RunResponse.Feedback rate(@PathVariable String runId, @Valid @RequestBody FeedbackRequest request,
                                     Authentication authentication) {
        return RunResponseMapper.toFeedback(
                feedbackService.rate(runId, authentication.getName(), request.rating(), request.comment()));
    }

    @DeleteMapping("/{runId}/feedback")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void removeRating(@PathVariable String runId, Authentication authentication) {
        feedbackService.remove(runId, authentication.getName());
    }

    /** Human-in-the-loop decision on the tool calls a paused run is waiting for; resumes the run (reviewers only). */
    @PostMapping("/{runId}/approval")
    public RunResponse decide(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request,
                              Authentication authentication) {
        String tools = pendingTools(runId);
        AgentRun run = orchestrator.decide(runId,
                new AgentOrchestrator.ApprovalDecision(request.approved(), request.comment(), authentication.getName()));
        auditDecision(authentication.getName(), runId, request, tools);
        return mapper.toResponse(run);
    }

    /** Same as {@link #decide}, streamed as Server-Sent Events while the run continues (see {@link RunStream}). */
    @PostMapping(path = "/{runId}/approval/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter decideStream(@PathVariable String runId, @Valid @RequestBody ApprovalRequest request,
                                   Authentication authentication) {
        AgentOrchestrator.ApprovalDecision decision =
                new AgentOrchestrator.ApprovalDecision(request.approved(), request.comment(), authentication.getName());
        String tools = pendingTools(runId);
        return RunStream.start(mapper, errors, listener -> {
            AgentRun run = orchestrator.decide(runId, decision, listener);
            auditDecision(authentication.getName(), runId, request, tools);
            return run;
        });
    }

    private String pendingTools(String runId) {
        return orchestrator.pendingApprovals(runId).stream()
                .map(call -> call.name() + " " + call.arguments())
                .collect(Collectors.joining("; "));
    }

    private void auditDecision(String reviewer, String runId, ApprovalRequest request, String tools) {
        String comment = request.comment() == null || request.comment().isBlank() ? "" : " — comment: " + request.comment();
        auditService.record(reviewer, request.approved() ? AuditAction.APPROVAL_APPROVED : AuditAction.APPROVAL_REJECTED,
                "run", runId, tools + comment);
    }
}
