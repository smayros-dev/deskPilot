package pl.piomin.services.deskpilot.controller;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.agent.AgentOrchestrator;
import pl.piomin.services.deskpilot.agent.AgentSession;
import pl.piomin.services.deskpilot.agent.SessionStore;
import pl.piomin.services.deskpilot.dto.ChatRequest;
import pl.piomin.services.deskpilot.dto.CreateSessionRequest;
import pl.piomin.services.deskpilot.dto.RunResponse;
import pl.piomin.services.deskpilot.dto.SessionResponse;
import pl.piomin.services.deskpilot.service.RunService;

@RestController
@RequestMapping("/api/sessions")
public class SessionController {

    static final String DEFAULT_USER = "demo-user";

    private final SessionStore sessionStore;
    private final AgentOrchestrator orchestrator;
    private final RunService runService;
    private final RunResponseMapper mapper;

    public SessionController(SessionStore sessionStore, AgentOrchestrator orchestrator, RunService runService,
                             RunResponseMapper mapper) {
        this.sessionStore = sessionStore;
        this.orchestrator = orchestrator;
        this.runService = runService;
        this.mapper = mapper;
    }

    @PostMapping
    public ResponseEntity<SessionResponse> create(@Valid @RequestBody(required = false) CreateSessionRequest request) {
        String userId = request == null || request.userId() == null || request.userId().isBlank()
                ? DEFAULT_USER : request.userId();
        AgentSession session = sessionStore.create(userId);
        return ResponseEntity.created(URI.create("/api/sessions/" + session.id())).body(toResponse(session));
    }

    @GetMapping("/{sessionId}")
    public SessionResponse get(@PathVariable String sessionId) {
        return toResponse(sessionStore.get(sessionId));
    }

    /** Runs the agent synchronously; with a local model on CPU this can take a minute or more. */
    @PostMapping("/{sessionId}/messages")
    public RunResponse chat(@PathVariable String sessionId, @Valid @RequestBody ChatRequest request) {
        return mapper.toResponse(orchestrator.chat(sessionId, request.message()));
    }

    @GetMapping("/{sessionId}/runs")
    public List<RunResponse> runs(@PathVariable String sessionId) {
        sessionStore.get(sessionId);
        return runService.findBySession(sessionId).stream().map(mapper::toResponse).toList();
    }

    private static SessionResponse toResponse(AgentSession session) {
        return new SessionResponse(session.id(), session.userId(), session.createdAt());
    }
}
