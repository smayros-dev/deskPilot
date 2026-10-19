package pl.piomin.services.deskpilot.agent;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

import io.micrometer.observation.Observation;
import io.micrometer.observation.ObservationRegistry;
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
import pl.piomin.services.deskpilot.model.AgentStep;
import pl.piomin.services.deskpilot.model.RunSource;
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
    private final ObservationRegistry observations;
    private final String systemPrompt;

    public AgentOrchestrator(ChatModel chatModel, ChatMemory chatMemory, ToolRegistry toolRegistry,
                             RunService runService, SessionService sessionService, TurnStore turnStore,
                             AgentSettingsService settingsService,
                             ChatOptionsFactory chatOptionsFactory, GuardrailService guardrails, AgentMetrics metrics,
                             ObservationRegistry observations,
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
        this.observations = observations;
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
        return chat(sessionId, userMessage, RunListener.NONE);
    }

    /** Like {@link #chat(String, String)}, reporting progress to the listener as the agent works. */
    public AgentRun chat(String sessionId, String userMessage, RunListener listener) {
        return observe("chat", () -> doChat(sessionId, userMessage, listener));
    }

    /**
     * Runs one evaluation scenario: like {@link #chat}, but with the given settings (e.g. another model) instead of the
     * live ones, and recorded as an evaluation run.
     */
    public AgentRun evaluate(String sessionId, String userMessage, AgentConfig config) {
        return observe("evaluate", () -> doChat(sessionId, userMessage, RunListener.NONE, config, RunSource.EVALUATION));
    }

    /** Closes a run paused for approval without running its tool calls (used by the evaluation runner). */
    public AgentRun abandon(String runId, String reason) {
        AgentRun run = runService.get(runId);
        return sessionService.exclusively(run.getSessionId(), () -> {
            turnStore.delete(runId);
            runService.addStep(runId, StepType.APPROVAL_DECISION, "ABANDONED", reason, false, 0);
            return runService.finish(runId, RunStatus.ESCALATED, reason, 0);
        });
    }

    private AgentRun doChat(String sessionId, String userMessage, RunListener listener) {
        return doChat(sessionId, userMessage, listener, null, RunSource.CHAT);
    }

    private AgentRun doChat(String sessionId, String userMessage, RunListener listener, AgentConfig override,
                            RunSource source) {
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
            AgentConfig config = override != null ? override : settingsService.current();
            AgentRun run = runService.start(sessionId, session.getUserId(), userMessage, config.chatModel(), source);
            tagCurrentObservation(run);
            ActiveTurn turn = new ActiveTurn(new RunContext(session.getUserId(), sessionId, run.getId(), config), userMessage);
            turn.listen(listener);
            listener.runStarted(run);
            return loop(turn);
        });
    }

    /** Applies a human decision to the tool calls a paused run is waiting on, then resumes the run. */
    public AgentRun decide(String runId, ApprovalDecision decision) {
        return decide(runId, decision, RunListener.NONE);
    }

    /** Like {@link #decide(String, ApprovalDecision)}, reporting progress to the listener as the run continues. */
    public AgentRun decide(String runId, ApprovalDecision decision, RunListener listener) {
        return observe("decide", () -> doDecide(runId, decision, listener));
    }

    private AgentRun doDecide(String runId, ApprovalDecision decision, RunListener listener) {
        AgentRun run = runService.get(runId);
        tagCurrentObservation(run);
        return sessionService.exclusively(run.getSessionId(), () -> {
            ActiveTurn turn = turnStore.find(runId)
                    .filter(ActiveTurn::isAwaitingApproval)
                    .orElseThrow(() -> new ConflictException("Run " + runId + " is not waiting for approval"));
            turnStore.delete(runId);
            turn.listen(listener);
            runService.resume(runId);
            listener.runStarted(runService.get(runId));
            record(turn, StepType.APPROVAL_DECISION, decision.approved() ? "APPROVED" : "REJECTED", decision.summary(), false, 0);
            metrics.approval(decision.approved() ? "approved" : "rejected");
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
                int iterations = runService.get(runId).getIterations();
                if (iterations >= config.maxIterations()) {
                    LOG.warn("Run {} hit the iteration limit ({})", runId, config.maxIterations());
                    return finish(turn, RunStatus.ESCALATED, MAX_ITERATIONS_ANSWER, startNanos);
                }
                turn.listener().thinking(iterations + 1);
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

                recordToolCallSteps(turn, reply);
                List<AssistantMessage.ToolCall> gated = reply.getToolCalls().stream()
                        .filter(call -> config.requiresApproval(call.name()))
                        .toList();
                if (!gated.isEmpty()) {
                    // Hold back every call of this message: their results must be returned together.
                    turn.awaitApproval(reply.getToolCalls());
                    turnStore.save(turn);
                    gated.forEach(call -> record(turn, StepType.APPROVAL_REQUESTED, call.name(), call.arguments(), false, 0));
                    runService.pauseForApproval(runId, elapsedMillis(startNanos));
                    return runService.get(runId);
                }
                executeToolCalls(turn, reply.getToolCalls(), null);
            }
        } catch (RuntimeException e) {
            LOG.error("Run {} failed", runId, e);
            record(turn, StepType.ERROR, e.getClass().getSimpleName(), e.getMessage(), true, 0);
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
            record(turn, StepType.THOUGHT, null, reply.getText().strip(), false, durationMillis);
        }
        return reply;
    }

    private void recordToolCallSteps(ActiveTurn turn, AssistantMessage reply) {
        reply.getToolCalls().forEach(call -> record(turn, StepType.TOOL_CALL, call.name(), call.arguments(), false, 0));
    }

    /** Saves a step of the trace and reports it to whoever follows the run. */
    private void record(ActiveTurn turn, StepType type, String name, String content, boolean error, long durationMillis) {
        AgentStep step = runService.addStep(turn.runId(), type, name, content, error, durationMillis);
        turn.listener().step(step);
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
            record(turn, StepType.TOOL_RESULT, call.name(), outcome.output(), outcome.error(),
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
            String output = Observation.createNotStarted("deskpilot.tool", observations)
                    .contextualName("tool " + call.name())
                    .lowCardinalityKeyValue("tool", call.name())
                    .observe(() -> tool.get().call(call.arguments(), context.toToolContext()));
            return new ToolOutcome(guardrails.limitToolOutput(output), false);
        } catch (RuntimeException e) {
            Throwable cause = NestedExceptionUtils.getMostSpecificCause(e);
            return new ToolOutcome(cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage(), true);
        }
    }

    private AgentRun finish(ActiveTurn turn, RunStatus status, String rawAnswer, long startNanos) {
        GuardrailService.Sanitized sanitized = guardrails.sanitizeOutput(rawAnswer);
        if (sanitized.redactions() > 0) {
            record(turn, StepType.GUARDRAIL, "output_redaction",
                    sanitized.redactions() + " sensitive value(s) redacted from the answer", false, 0);
        }
        String answer = sanitized.text();
        record(turn, StepType.ANSWER, status.name(), answer, status == RunStatus.FAILED, 0);
        AgentRun run = runService.finish(turn.runId(), status, answer, elapsedMillis(startNanos));
        metrics.runFinished(status, run.getActiveMillis());
        // Short-term memory keeps only the question and the answer; tool chatter stays in the run trace.
        chatMemory.add(turn.context().sessionId(), List.of(new UserMessage(turn.userInput()), new AssistantMessage(answer)));
        return run;
    }

    /**
     * One trace span per request on a run (OpenTelemetry through Micrometer Observation); the model calls made by
     * Spring AI and the tool calls appear as its children.
     */
    private AgentRun observe(String operation, Supplier<AgentRun> work) {
        return Observation.createNotStarted("deskpilot.run", observations)
                .contextualName("agent " + operation)
                .lowCardinalityKeyValue("operation", operation)
                .observe(work);
    }

    private void tagCurrentObservation(AgentRun run) {
        Observation current = observations.getCurrentObservation();
        if (current != null) {
            current.highCardinalityKeyValue("run.id", run.getId());
            current.highCardinalityKeyValue("session.id", run.getSessionId());
            current.lowCardinalityKeyValue("model", run.getModel() == null ? "unknown" : run.getModel());
        }
    }

    private static long tokens(Integer value) {
        return value == null ? 0 : value;
    }

    private static long elapsedMillis(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }
}
