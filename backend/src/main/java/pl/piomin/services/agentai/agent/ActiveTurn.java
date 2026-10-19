package pl.piomin.services.agentai.agent;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import pl.piomin.services.agentai.agent.tool.RunContext;

/**
 * Working state of the run currently in progress for a session: the user message, the assistant/tool
 * messages exchanged so far, and the tool calls held back while waiting for a human decision.
 * Only the final question and answer are moved to chat memory when the turn ends.
 */
final class ActiveTurn {

    private final RunContext context;
    private final String userInput;
    private final List<Message> messages = new ArrayList<>();
    private List<AssistantMessage.ToolCall> pendingToolCalls = List.of();

    ActiveTurn(RunContext context, String userInput) {
        this.context = context;
        this.userInput = userInput;
        this.messages.add(new UserMessage(userInput));
    }

    void add(Message message) {
        messages.add(message);
    }

    void awaitApproval(List<AssistantMessage.ToolCall> toolCalls) {
        this.pendingToolCalls = List.copyOf(toolCalls);
    }

    List<AssistantMessage.ToolCall> takePendingToolCalls() {
        List<AssistantMessage.ToolCall> calls = pendingToolCalls;
        pendingToolCalls = List.of();
        return calls;
    }

    boolean isAwaitingApproval() {
        return !pendingToolCalls.isEmpty();
    }

    List<AssistantMessage.ToolCall> pendingToolCalls() {
        return pendingToolCalls;
    }

    List<Message> messages() {
        return List.copyOf(messages);
    }

    RunContext context() {
        return context;
    }

    String runId() {
        return context.runId();
    }

    String userInput() {
        return userInput;
    }
}
