package pl.piomin.services.deskpilot.agent;

import java.util.Set;

/**
 * Immutable snapshot of the agent settings taken when a run starts, so that changing the settings
 * mid-run (e.g. while a run waits for approval) does not change how that run behaves.
 */
public record AgentConfig(
        String chatModel,
        double temperature,
        double topP,
        int contextWindow,
        int maxOutputTokens,
        boolean thinking,
        int maxIterations,
        int ragTopK,
        double ragSimilarityThreshold,
        Set<String> disabledTools,
        Set<String> approvalTools,
        String systemPrompt) {

    public boolean isToolEnabled(String tool) {
        return !disabledTools.contains(tool);
    }

    public boolean requiresApproval(String tool) {
        return approvalTools.contains(tool);
    }
}
