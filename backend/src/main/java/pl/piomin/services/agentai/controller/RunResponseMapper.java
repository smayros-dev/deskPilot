package pl.piomin.services.agentai.controller;

import java.util.List;

import org.springframework.stereotype.Component;
import pl.piomin.services.agentai.agent.AgentOrchestrator;
import pl.piomin.services.agentai.dto.RunResponse;
import pl.piomin.services.agentai.model.AgentRun;
import pl.piomin.services.agentai.model.RunStatus;
import pl.piomin.services.agentai.service.RunService;

@Component
class RunResponseMapper {

    private final RunService runService;
    private final AgentOrchestrator orchestrator;

    RunResponseMapper(RunService runService, AgentOrchestrator orchestrator) {
        this.runService = runService;
        this.orchestrator = orchestrator;
    }

    RunResponse toResponse(AgentRun run) {
        List<RunResponse.Step> steps = runService.steps(run.getId()).stream()
                .map(s -> new RunResponse.Step(s.getId(), s.getType(), s.getName(), s.getContent(), s.isError(),
                        s.getDurationMillis(), s.getCreatedAt()))
                .toList();
        List<RunResponse.PendingApproval> pending = run.getStatus() == RunStatus.AWAITING_APPROVAL
                ? orchestrator.pendingApprovals(run.getId()).stream()
                        .map(call -> new RunResponse.PendingApproval(call.id(), call.name(), call.arguments()))
                        .toList()
                : List.of();
        RunResponse.Stats stats = new RunResponse.Stats(run.getIterations(), run.getToolCalls(), run.getToolErrors(),
                run.getPromptTokens(), run.getCompletionTokens(), run.getActiveMillis());
        return new RunResponse(run.getId(), run.getSessionId(), run.getModel(), run.getStatus(), run.getUserInput(),
                run.getFinalAnswer(), steps, pending, stats, run.getCreatedAt(), run.getCompletedAt());
    }
}
