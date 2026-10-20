package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.service.FeedbackService;
import pl.piomin.services.deskpilot.service.RunService;

@Component
class RunResponseMapper {

    private final RunService runService;
    private final AgentOrchestrator orchestrator;
    private final FeedbackService feedbackService;

    RunResponseMapper(RunService runService, AgentOrchestrator orchestrator, FeedbackService feedbackService) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.feedbackService = feedbackService;
    }

    RunResponse toResponse(AgentRun run) {
        List<RunResponse.Step> steps = runService.steps(run.getId()).stream().map(RunResponseMapper::toStep).toList();
        List<RunResponse.PendingApproval> pending = run.getStatus() == RunStatus.AWAITING_APPROVAL
                ? orchestrator.pendingApprovals(run.getId()).stream()
                        .map(call -> new RunResponse.PendingApproval(call.id(), call.name(), call.arguments()))
                        .toList()
                : List.of();
        RunResponse.Stats stats = new RunResponse.Stats(run.getIterations(), run.getToolCalls(), run.getToolErrors(),
                run.getPromptTokens(), run.getCompletionTokens(), run.getActiveMillis());
        return new RunResponse(run.getId(), run.getSessionId(), run.getUserId(), run.getModel(), run.getSpecialist(), run.getStatus(), run.getUserInput(),
                run.getFinalAnswer(), steps, pending, stats, run.getCreatedAt(), run.getCompletedAt(),
                run.isFinished() ? feedbackService.find(run.getId()).map(RunResponseMapper::toFeedback).orElse(null) : null);
    }

    static RunResponse.Feedback toFeedback(RunFeedback feedback) {
        return new RunResponse.Feedback(feedback.getRating(), feedback.getComment(), feedback.getUpdatedAt());
    }

    static RunResponse.Step toStep(AgentStep step) {
        return new RunResponse.Step(step.getId(), step.getType(), step.getName(), step.getContent(), step.isError(),
                step.getDurationMillis(), step.getCreatedAt());
    }
}
