package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunFeedback;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.repository.ExperimentRepository;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.FeedbackService;
import pl.piomin.services.deskpilot.service.RunService;

@Component
class RunResponseMapper {

    private final RunService runService;
    private final AgentOrchestrator orchestrator;
    private final FeedbackService feedbackService;
    private final ExperimentRepository experiments;

    RunResponseMapper(RunService runService, AgentOrchestrator orchestrator, FeedbackService feedbackService,
                      ExperimentRepository experiments) {
        this.runService = runService;
        this.orchestrator = orchestrator;
        this.feedbackService = feedbackService;
        this.experiments = experiments;
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
        return new RunResponse(run.getId(), run.getSessionId(), run.getUserId(), run.getModel(), run.getSpecialist(), experiment(run), run.getStatus(), run.getUserInput(),
                run.getFinalAnswer(), steps, pending, stats, run.getCreatedAt(), run.getCompletedAt(),
                run.isFinished() ? feedbackService.find(run.getId()).map(RunResponseMapper::toFeedback).orElse(null) : null);
    }

    /**
     * Experiment and variant of the run, for reviewers and admins only. Without a signed-in caller (live events pushed
     * to the run's owner) it is left out as well.
     */
    private RunResponse.Experiment experiment(AgentRun run) {
        if (run.getExperimentId() == null) {
            return null;
        }
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !authentication.isAuthenticated() || !CurrentUser.of(authentication).canSeeAll()) {
            return null;
        }
        String name = experiments.findById(run.getExperimentId()).map(e -> e.getName()).orElse(null);
        return new RunResponse.Experiment(run.getExperimentId(), name, run.getVariant());
    }

    static RunResponse.Feedback toFeedback(RunFeedback feedback) {
        return new RunResponse.Feedback(feedback.getRating(), feedback.getComment(), feedback.getUpdatedAt());
    }

    static RunResponse.Step toStep(AgentStep step) {
        return new RunResponse.Step(step.getId(), step.getType(), step.getName(), step.getContent(), step.isError(),
                step.getDurationMillis(), step.getCreatedAt());
    }
}
