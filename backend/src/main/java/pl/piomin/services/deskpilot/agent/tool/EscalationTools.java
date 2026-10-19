package pl.piomin.services.deskpilot.agent.tool;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

@Component
public class EscalationTools implements AgentTools {

    @Tool(name = "escalate_to_human", description = """
            Hand the conversation to a human helpdesk engineer. Use it when the user asks for a human, \
            is blocked by a security incident, or you cannot help after searching the knowledge base.""")
    public String escalateToHuman(
            @ToolParam(description = "Why a human is needed, one sentence") String reason,
            ToolContext toolContext) {
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason must not be blank");
        }
        RunContext.from(toolContext).requestEscalation(reason.strip());
        return "Escalated. Tell the user a helpdesk engineer will contact them.";
    }
}
