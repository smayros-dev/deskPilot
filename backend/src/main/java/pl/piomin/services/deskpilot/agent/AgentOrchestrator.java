package pl.piomin.services.deskpilot.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;
import pl.piomin.services.deskpilot.agent.tool.RunContext;
import pl.piomin.services.deskpilot.agent.tool.ToolRegistry;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.guardrail.GuardrailService;
import pl.piomin.services.deskpilot.guardrail.GuardrailViolationException;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentSession;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.StepType;
import pl.piomin.services.deskpilot.service.RunService;

/**
 * The ReAct loop: ask the model, run the tools it requests, feed the results back, repeat until it
 * answers. Tool calls marked {@code @RequiresApproval} pause the run until a human decides.
 * Spring AI 2.0's {@link ChatModel} never executes tools itself, so every call goes through
 * {@link #executeToolCalls}, which records it and enforces the approval decision. Sessions, chat memory and
 * paused runs live in the database, so a run waiting for approval can be resumed after a restart.
 */
@Service
public class AgentOrchestrator {

    private static final Logger LOG = LoggerFactory.getLogger(AgentOrchestrator.class);

    static final String MAX_ITERATIONS_ANSWER =
            "I could not finish this request on my own, so I have handed it to a helpdesk engineer who will follow up.";
    static final String EMPTY_ANSWER = "Sorry, I could not produce an answer. Please rephrase your question.";

    private final ChatModel chatModel;
    private final ChatMemory chatMemory;
    private final ToolRegistry toolRegistry;
    private final RunService runService;
    private final SessionService sessionService;
    private final TurnStore turnStore;
    private final AgentSettingsService settingsService;
    private final ChatOptionsFactory chatOptionsFactory;
    private final GuardrailService guardrails;
    private final AgentMetrics metrics;
    private final String systemPrompt;

    public AgentOrchestrator(ChatModel chatModel, ChatMemory chatMemory, ToolRegistry toolRegistry,
                             RunService runService, SessionService sessionService, TurnStore turnStore,
                             AgentSettingsService settingsService,
                             ChatOptionsFactory chatOptionsFactory, GuardrailService guardrails, AgentMetrics metrics,
                             @Value("classpath:prompts/system-prompt.txt") Resource systemPrompt) throws IOException {
        this.chatModel = chatModel;
        this.chatMemory = chatMemory;
        this.toolRegistry = toolRegistry;
        this.runService = runService;
        this.sessionService = sessionService;
        this.turnStore = turnStore;
        this.settingsService = settingsService;
        this.chatOptionsFactory = chatOptionsFactory;
        this.guardrails = guardrails;
        this.metrics = metrics;
        this.systemPrompt = systemPrompt.getContentAsString(StandardCharsets.UTF_8);
    }

    /** @param reviewer user name of the human who decided, recorded in the trace */
    public record ApprovalDecision(boolean approved, String comment, String reviewer) {

        String summary() {
            return comment == null || comment.isBlank() ? "by " + reviewer : "by " + reviewer + ": " + comment;
        }
    }

    /** Handles a new user message; returns the run, finished or paused for approval. */
    public AgentRun chat(String sessionId, String userMessage) {
        AgentSession session = sessionService.get(sessionId);
        try {
            guardrails.checkUserInput(userMessage);
        } catch (GuardrailViolationException e) {
            metrics.guardrailBlocked(e.getRule());
            throw e;
        }
        return sessionService.exclusively(sessionId, () -> {
            if (turnStore.isPaused(sessionId)) {
                throw new ConflictException("A run in session " + sessionId + " is waiting for approval; approve or reject it first");
            }
            AgentConfig config = settingsService.current();
            AgentRun run = runService.start(sessionId, session.getUserId(), userMessage, config.chatModel());
            ActiveTurn turn = new ActiveTurn(new RunContext(session.getUserId(), sessionId, run.getId(), config), userMessage);
            return loop(turn);
        });
    }

    /** Applies a human decision to the tool calls a paused run is waiting on, then resumes the run. */
    public AgentRun decide(String runId, ApprovalDecision decision) {
        AgentRun run = runService.get(runId);
        return sessionService.exclusively(run.getSessionId(), () -> {
            ActiveTurn turn = turnStore.find(runId)
                    .filter(ActiveTurn::isAwaitingApproval)
                    .orElseThrow(() -> new ConflictException("Run " + runId + " is not waiting for approval"));
            turnStore.delete(runId);
            runService.addStep(runId, StepType.APPROVAL_DECISION, decision.approved() ? "APPROVED" : "REJECTED",
                    decision.summary(), false, 0);
            metrics.approval(decision.approved() ? "approved" : "rejected");
            runService.resume(runId);
            long start = System.nanoTime();
            executeToolCalls(turn, turn.takePendingToolCalls(), decision);
            return loop(turn, start);
        });
    }

    /** Tool calls the run is currently waiting on, empty if it is not paused. */
    public List<AssistantMessage.ToolCall> pendingApprovals(String runId) {
        return turnStore.find(runId)
                .map(ActiveTurn::pendingToolCalls)
                .orElse(List.of());
    }

    private AgentRun loop(ActiveTurn turn) {
        return loop(turn, System.nanoTime());
    }

    private AgentRun loop(ActiveTurn turn, long startNanos) {
        String runId = turn.runId();
        AgentConfig config = turn.context().config();
        try {
            while (true) {
                if (runService.get(runId).getIterations() >= config.maxIterations()) {
                    LOG.warn("Run {} hit the iteration limit ({})", runId, config.maxIterations());
                    return finish(turn, RunStatus.ESCALATED, MAX_ITERATIONS_ANSWER, startNanos);
                }
                AssistantMessage reply = callModel(turn);
                turn.add(reply);

                if (!reply.hasToolCalls()) {
                    String answer = reply.getText() == null ? "" : reply.getText().strip();
                    if (answer.isEmpty()) {
                        return finish(turn, RunStatus.FAILED, EMPTY_ANSWER, startNanos);
                    }
                    RunStatus status = turn.context().escalationReason().isPresent() ? RunStatus.ESCALATED : RunStatus.COMPLETED;
                    return finish(turn, status, answer, startNanos);
                }

                recordToolCallSteps(runId, reply);
                List<AssistantMessage.ToolCall> gated = reply.getToolCalls().stream()
                        .filter(call -> config.requiresApproval(call.name()))
                        .toList();
                if (!gated.isEmpty()) {
                    // Hold back every call of this message: their results must be returned together.
                    turn.awaitApproval(reply.getToolCalls());
                    turnStore.save(turn);
                    gated.forEach(call -> runService.addStep(runId, StepType.APPROVAL_REQUESTED, call.name(),
                            call.arguments(), false, 0));
                    runService.pauseForApproval(runId, elapsedMillis(startNanos));
                    return runService.get(runId);
                }
                executeToolCalls(turn, reply.getToolCalls(), null);
            }
        } catch (RuntimeException e) {
            LOG.error("Run {} failed", runId, e);
            runService.addStep(runId, StepType.ERROR, e.getClass().getSimpleName(), e.getMessage(), true, 0);
            AgentRun failed = runService.finish(runId, RunStatus.FAILED, null, elapsedMillis(startNanos));
            metrics.runFinished(RunStatus.FAILED, failed.getActiveMillis());
            throw e;
        }
    }

    private AssistantMessage callModel(ActiveTurn turn) {
        AgentConfig config = turn.context().config();
        List<Message> messages = new ArrayList<>();
        messages.add(new SystemMessage(config.systemPrompt() != null ? config.systemPrompt() : systemPrompt));
        messages.addAll(chatMemory.get(turn.context().sessionId()));
        messages.addAll(turn.messages());
        List<ToolCallback> tools = toolRegistry.all().stream()
                .filter(tool -> config.isToolEnabled(tool.getToolDefinition().name()))
                .toList();

        long start = System.nanoTime();
        ChatResponse response = chatModel.call(new Prompt(messages, chatOptionsFactory.create(config, tools)));
        long durationMillis = elapsedMillis(start);

        Usage usage = response.getMetadata().getUsage();
        long promptTokens = tokens(usage == null ? null : usage.getPromptTokens());
        long completionTokens = tokens(usage == null ? null : usage.getCompletionTokens());
        runService.recordModelCall(turn.runId(), promptTokens, completionTokens);
        metrics.modelCall(durationMillis, promptTokens, completionTokens);
        AssistantMessage reply = response.getResult().getOutput();
        // Text that accompanies tool calls is the model's reasoning step (the "thought" in ReAct).
        if (reply.hasToolCalls() && reply.getText() != null && !reply.getText().isBlank()) {
            runService.addStep(turn.runId(), StepType.THOUGHT, null, reply.getText().strip(), false, durationMillis);
        }
        return reply;
    }

    private void recordToolCallSteps(String runId, AssistantMessage reply) {
        reply.getToolCalls().forEach(call ->
                runService.addStep(runId, StepType.TOOL_CALL, call.name(), call.arguments(), false, 0));
    }

    /**
     * Runs the tool calls and appends their results as one tool-response message. Failures become
     * error results the model can react to (retry with other arguments, or explain), never exceptions.
     */
    private void executeToolCalls(ActiveTurn turn, List<AssistantMessage.ToolCall> calls, ApprovalDecision decision) {
        List<ToolResponseMessage.ToolResponse> responses = new ArrayList<>();
        for (AssistantMessage.ToolCall call : calls) {
            long start = System.nanoTime();
            ToolOutcome outcome = executeToolCall(turn.context(), call, decision);
            runService.recordToolCall(turn.runId(), outcome.error());
            metrics.toolCall(call.name(), outcome.error());
            runService.addStep(turn.runId(), StepType.TOOL_RESULT, call.name(), outcome.output(), outcome.error(),
                    elapsedMillis(start));
            responses.add(new ToolResponseMessage.ToolResponse(call.id(), call.name(),
                    outcome.error() ? "ERROR: " + outcome.output() : outcome.output()));
        }
        turn.add(ToolResponseMessage.builder().responses(responses).build());
    }

    private record ToolOutcome(String output, boolean error) {
    }

    private ToolOutcome executeToolCall(RunContext context, AssistantMessage.ToolCall call, ApprovalDecision decision) {
        Optional<ToolCallback> tool = toolRegistry.find(call.name()).filter(t -> context.config().isToolEnabled(call.name()));
        if (tool.isEmpty()) {
            return new ToolOutcome("Unknown or disabled tool '" + call.name() + "'. Use only the tools you were given.", true);
        }
        if (decision != null && !decision.approved() && context.config().requiresApproval(call.name())) {
            String reason = decision.comment() == null || decision.comment().isBlank() ? "no reason given" : decision.comment();
            return new ToolOutcome("Rejected by the human reviewer (" + reason + "). Do not retry; tell the user.", true);
        }
        try {
            return new ToolOutcome(guardrails.limitToolOutput(tool.get().call(call.arguments(), context.toToolContext())), false);
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            return new ToolOutcome(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), true);
        }
    }

    private AgentRun finish(ActiveTurn turn, RunStatus status, String rawAnswer, long startNanos) {
        GuardrailService.Sanitized sanitized = guardrails.sanitizeOutput(rawAnswer);
        if (sanitized.redactions() > 0) {
            runService.addStep(turn.runId(), StepType.GUARDRAIL, "output_redaction",
                    sanitized.redactions() + " sensitive value(s) redacted from the answer", false, 0);
        }
        String answer = sanitized.text();
        runService.addStep(turn.runId(), StepType.ANSWER, status.name(), answer, status == RunStatus.FAILED, 0);
        AgentRun run = runService.finish(turn.runId(), status, answer, elapsedMillis(startNanos));
        metrics.runFinished(status, run.getActiveMillis());
        // Short-term memory keeps only the question and the answer; tool chatter stays in the run trace.
        chatMemory.add(turn.context().sessionId(), List.of(new UserMessage(turn.userInput()), new AssistantMessage(answer)));
        return run;
    }

    private static long tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
