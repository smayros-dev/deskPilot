package pl.piomin.services.deskpilot.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.agent.RunListener;
import pl.piomin.services.deskpilot.agent.SessionService;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.exception.GlobalExceptionHandler;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.AgentSession;
import pl.piomin.services.deskpilot.notifications.ContactService;
import pl.piomin.services.deskpilot.service.RunService;

/** Conversations of the authenticated user; other users' sessions answer 404. */
@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    private final SessionService sessionService;
    private final AgentOrchestrator orchestrator;
    private final RunService runService;
    private final RunResponseMapper mapper;
    private final ContactService contacts;
    private final GlobalExceptionHandler errors;

    public SessionController(SessionService sessionService, AgentOrchestrator orchestrator, RunService runService,
                             RunResponseMapper mapper, GlobalExceptionHandler errors,
                             ContactService contacts) {
        this.contacts = contacts;
        this.sessionService = sessionService;
        this.orchestrator = orchestrator;
        this.runService = runService;
        this.mapper = mapper;
        this.errors = errors;
    }

    /** Starts a conversation for the caller; the user id comes from the token, never from the request. */
    @PostMapping
    public ResponseEntity<SessionResponse> create(Authentication authentication) {
        contacts.remember(authentication); // e-mail address for ticket confirmations (12j)
        AgentSession session = sessionService.create(authentication.getName());
        return ResponseEntity.created(URI.create("/api/sessions/" + session.getId())).body(toResponse(session));
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@PathVariable String sessionId, Authentication authentication) {
        return toResponse(ownSession(sessionId, authentication));
    }

    /** Runs the agent synchronously; with a local model on CPU this can take a minute or more. */
    @PostMapping("/{sessionId}/messages")
    public RunResponse chat(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request,
                            Authentication authentication) {
        ownSession(sessionId, authentication);
        return mapper.toResponse(orchestrator.chat(sessionId, request.message(), request.image(), RunListener.NONE));
    }

    /** Same as {@link #chat}, streamed as Server-Sent Events (see {@link RunStream}). */
    @PostMapping(path = "/{sessionId}/messages/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter chatStream(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request,
                                 Authentication authentication) {
        ownSession(sessionId, authentication);
        return RunStream.start(mapper, errors, listener -> orchestrator.chat(sessionId, request.message(), request.image(), listener));
    }

    @GetMapping("/{sessionId}/runs")
    public List<RunResponse> runs(@PathVariable String sessionId, Authentication authentication) {
        ownSession(sessionId, authentication);
        return runService.findBySession(sessionId).stream().map(mapper::toResponse).toList();
    }

    private AgentSession ownSession(String sessionId, Authentication authentication) {
        AgentSession session = sessionService.get(sessionId);
        if (!session.getUserId().equals(authentication.getName())) {
            throw new NotFoundException("Session " + sessionId + " not found");
        }
        return session;
    }

    private static SessionResponse toResponse(AgentSession session) {
        return new SessionResponse(session.getId(), session.getUserId(), session.getCreatedAt());
    }
}
