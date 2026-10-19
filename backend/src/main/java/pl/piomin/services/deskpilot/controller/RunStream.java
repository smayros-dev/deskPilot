package pl.piomin.services.deskpilot.controller;

import java.io.IOException;
import java.time.Duration;
import java.util.Map;
import java.util.function.Function;

import org.springframework.http.MediaType;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.piomin.services.deskpilot.agent.RunListener;
import pl.piomin.services.deskpilot.exception.GlobalExceptionHandler;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.AgentStep;

/**
 * Streams a run as Server-Sent Events while the agent works on it, so the UI can show each step instead of waiting
 * minutes for the whole answer. Events: {@code run} (started or resumed), {@code thinking} (a model call begins),
 * {@code step} (a trace step was saved), then {@code done} with the final run, or {@code error} with a Problem Detail.
 * The run continues even if the browser disconnects.
 */
final class RunStream implements RunListener {

    /** Upper bound for one streamed run; on CPU a long multi-tool run takes a few minutes. */
    static final Duration TIMEOUT = Duration.ofMinutes(30);

    private final SseEmitter emitter = new SseEmitter(TIMEOUT.toMillis());
    private final RunResponseMapper mapper;
    private volatile boolean open = true;

    private RunStream(RunResponseMapper mapper) {
        this.mapper = mapper;
        emitter.onCompletion(() -> open = false);
        emitter.onTimeout(() -> open = false);
        emitter.onError(e -> open = false);
    }

    /** Runs the work on a virtual thread and returns the emitter immediately. */
    static SseEmitter start(RunResponseMapper mapper, GlobalExceptionHandler errors, Function<RunListener, AgentRun> work) {
        RunStream stream = new RunStream(mapper);
        Thread.ofVirtual().name("run-stream").start(() -> {
            try {
                AgentRun run = work.apply(stream);
                stream.send("done", mapper.toResponse(run));
            } catch (RuntimeException e) {
                stream.send("error", errors.toProblem(e));
            } finally {
                stream.complete();
            }
        });
        return stream.emitter;
    }

    @Override
    public void runStarted(AgentRun run) {
        send("run", mapper.toResponse(run));
    }

    @Override
    public void thinking(int iteration) {
        send("thinking", Map.of("iteration", iteration));
    }

    @Override
    public void step(AgentStep step) {
        send("step", RunResponseMapper.toStep(step));
    }

    private void send(String event, Object data) {
        if (!open) {
            return;
        }
        try {
            emitter.send(SseEmitter.event().name(event).data(data, MediaType.APPLICATION_JSON));
        } catch (IOException | IllegalStateException e) {
            // The browser went away; the run goes on and its result stays in the database.
            open = false;
        }
    }

    private void complete() {
        if (open) {
            emitter.complete();
        }
    }
}
