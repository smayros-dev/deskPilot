package pl.piomin.services.deskpilot.agent.tool;

import java.util.List;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.guardrail.GuardrailService;
import pl.piomin.services.deskpilot.model.MemoryFact;
import pl.piomin.services.deskpilot.service.MemoryService;

@Component
public class MemoryTools implements AgentTools {

    static final int MAX_FACT_LENGTH = 500;

    private final MemoryService memoryService;
    private final GuardrailService guardrails;

    public MemoryTools(MemoryService memoryService, GuardrailService guardrails) {
        this.memoryService = memoryService;
        this.guardrails = guardrails;
    }

    @Tool(name = "save_memory", description = """
            Remember a durable fact or preference about the current user for future conversations, \
            e.g. 'uses a MacBook', 'prefers short answers'. Never store passwords or other secrets.""")
    public String saveMemory(
            @ToolParam(description = "The fact to remember, one short sentence") String fact,
            ToolContext toolContext) {
        if (fact == null || fact.isBlank() || fact.length() > MAX_FACT_LENGTH) {
            throw new IllegalArgumentException("fact must be 1-" + MAX_FACT_LENGTH + " characters");
        }
        if (guardrails.containsSensitiveData(fact)) {
            throw new IllegalArgumentException("Refused: the fact looks like a password, key or card number. Never store secrets.");
        }
        memoryService.save(RunContext.from(toolContext).userId(), fact);
        return "Saved.";
    }

    @Tool(name = "recall_memories", description = """
            Recall facts previously saved about the current user (device, preferences, past issues). \
            Use it when personal context would change the answer.""")
    public List<String> recallMemories(
            @ToolParam(description = "Keywords to look for; empty string returns the most recent facts", required = false) String query,
            ToolContext toolContext) {
        return memoryService.recall(RunContext.from(toolContext).userId(), query).stream()
                .map(MemoryFact::getContent)
                .toList();
    }
}
