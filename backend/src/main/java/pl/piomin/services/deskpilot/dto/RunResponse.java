package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;

public record RunResponse(
        String id,
        String sessionId,
        String model,
        RunStatus status,
        String userInput,
        String answer,
        List<Step> steps,
        List<PendingApproval> pendingApprovals,
        Stats stats,
        Instant createdAt,
        Instant completedAt) {

    public record Step(long id, StepType type, String name, String content, boolean error, long durationMillis,
                       Instant createdAt) {
    }

    public record PendingApproval(String toolCallId, String toolName, String arguments) {
    }

    public record Stats(int iterations, int toolCalls, int toolErrors, long promptTokens, long completionTokens,
                        long activeMillis) {
    }
}
