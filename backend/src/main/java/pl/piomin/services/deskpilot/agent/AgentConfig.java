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
        boolean groundingCheck,
        boolean hybridSearch,
        boolean selfCheck,
        String visionModel,
        String chatProvider,
        String visionProvider,
        Set<String> disabledTools,
        Set<String> approvalTools,
        String systemPrompt) {

    /** The same settings with another chat model, e.g. to evaluate a model without switching the live agent. */
    public AgentConfig withChatModel(String model) {
        return new AgentConfig(model, temperature, topP, contextWindow, maxOutputTokens, thinking, maxIterations, ragTopK,
                ragSimilarityThreshold, groundingCheck, hybridSearch, selfCheck, visionModel, chatProvider, visionProvider, disabledTools, approvalTools,
                systemPrompt);
    }

    /** The same settings with another provider and model, e.g. to evaluate a cloud model. */
    public AgentConfig withChat(String provider, String model) {
        return new AgentConfig(model, temperature, topP, contextWindow, maxOutputTokens, thinking, maxIterations, ragTopK,
                ragSimilarityThreshold, groundingCheck, hybridSearch, selfCheck, visionModel, provider, visionProvider,
                disabledTools, approvalTools, systemPrompt);
    }

    /** Settings for reading a screenshot: the vision provider and model take the place of the chat ones. */
    public AgentConfig forVision() {
        return withChat(visionProvider == null ? "ollama" : visionProvider, visionModel);
    }

    /** How the model is shown in runs and metrics: cloud models carry their provider, e.g. "mistral:mistral-small-latest". */
    public String modelLabel() {
        return chatProvider == null || "ollama".equals(chatProvider) ? chatModel : chatProvider + ":" + chatModel;
    }

    public boolean isToolEnabled(String tool) {
        return !disabledTools.contains(tool);
    }

    public boolean requiresApproval(String tool) {
        return approvalTools.contains(tool);
    }
}
