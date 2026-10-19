package pl.piomin.services.deskpilot.agent;

import java.util.List;
import java.util.Optional;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.agent.tool.RunContext;
import pl.piomin.services.deskpilot.model.PendingTurn;
import pl.piomin.services.deskpilot.repository.PendingTurnRepository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Saves the working state of a run paused for approval, so a human can approve it after a restart.
 * Spring AI messages are mapped to plain records first: only text, tool calls and tool results matter here.
 */
@Component
@Transactional(readOnly = true)
public class TurnStore {

    record State(String userId, String sessionId, String runId, AgentConfig config, String escalationReason,
                 String userInput, List<StoredMessage> messages, List<StoredToolCall> pendingToolCalls) {
    }

    enum Kind { USER, ASSISTANT, TOOL }

    record StoredMessage(Kind kind, String text, List<StoredToolCall> toolCalls, List<StoredToolResponse> toolResponses) {
    }

    record StoredToolCall(String id, String type, String name, String arguments) {
    }

    record StoredToolResponse(String id, String name, String data) {
    }

    private final PendingTurnRepository repository;
    private final JsonMapper jsonMapper;

    public TurnStore(PendingTurnRepository repository, JsonMapper jsonMapper) {
        this.repository = repository;
        this.jsonMapper = jsonMapper;
    }

    @Transactional
    void save(ActiveTurn turn) {
        RunContext context = turn.context();
        State state = new State(context.userId(), context.sessionId(), context.runId(), context.config(),
                context.escalationReason().orElse(null), turn.userInput(),
                turn.messages().stream().map(TurnStore::toStored).toList(),
                turn.pendingToolCalls().stream().map(TurnStore::toStored).toList());
        repository.save(new PendingTurn(context.runId(), context.sessionId(), jsonMapper.writeValueAsString(state)));
    }

    Optional<ActiveTurn> find(String runId) {
        return repository.findById(runId).map(row -> restore(jsonMapper.readValue(row.getState(), State.class)));
    }

    public boolean isPaused(String sessionId) {
        return repository.existsBySessionId(sessionId);
    }

    @Transactional
    void delete(String runId) {
        repository.deleteById(runId);
    }

    private static ActiveTurn restore(State state) {
        RunContext context = new RunContext(state.userId(), state.sessionId(), state.runId(), state.config());
        if (state.escalationReason() != null) {
            context.requestEscalation(state.escalationReason());
        }
        return new ActiveTurn(context, state.userInput(),
                state.messages().stream().map(TurnStore::toMessage).toList(),
                state.pendingToolCalls().stream().map(TurnStore::toToolCall).toList());
    }

    private static StoredMessage toStored(Message message) {
        return switch (message) {
            case AssistantMessage m -> new StoredMessage(Kind.ASSISTANT, m.getText(),
                    m.getToolCalls().stream().map(TurnStore::toStored).toList(), List.of());
            case ToolResponseMessage m -> new StoredMessage(Kind.TOOL, null, List.of(), m.getResponses().stream()
                    .map(r -> new StoredToolResponse(r.id(), r.name(), r.responseData())).toList());
            case UserMessage m -> new StoredMessage(Kind.USER, m.getText(), List.of(), List.of());
            default -> throw new IllegalArgumentException("Unexpected message in a turn: " + message.getMessageType());
        };
    }

    private static StoredToolCall toStored(AssistantMessage.ToolCall call) {
        return new StoredToolCall(call.id(), call.type(), call.name(), call.arguments());
    }

    private static Message toMessage(StoredMessage stored) {
        return switch (stored.kind()) {
            case USER -> new UserMessage(stored.text());
            case ASSISTANT -> AssistantMessage.builder().content(stored.text())
                    .toolCalls(stored.toolCalls().stream().map(TurnStore::toToolCall).toList()).build();
            case TOOL -> ToolResponseMessage.builder().responses(stored.toolResponses().stream()
                    .map(r -> new ToolResponseMessage.ToolResponse(r.id(), r.name(), r.data())).toList()).build();
        };
    }

    private static AssistantMessage.ToolCall toToolCall(StoredToolCall call) {
        return new AssistantMessage.ToolCall(call.id(), call.type(), call.name(), call.arguments());
    }
}
