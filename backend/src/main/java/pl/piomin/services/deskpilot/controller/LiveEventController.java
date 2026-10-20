package pl.piomin.services.deskpilot.controller;

import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import pl.piomin.services.deskpilot.events.LiveEventHub;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.RunService;

/** The signed-in user's live updates, as Server-Sent Events (see {@link LiveEventRelay} for the events). */
@RestController
@RequestMapping("/api/events")
public class LiveEventController {

    private final LiveEventHub hub;
    private final RunService runService;

    public LiveEventController(LiveEventHub hub, RunService runService) {
        this.hub = hub;
        this.runService = runService;
    }

    @GetMapping(produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter events(Authentication authentication) {
        CurrentUser user = CurrentUser.of(authentication);
        SseEmitter emitter = hub.subscribe(user);
        if (user.canSeeAll()) {
            // Reviewers start with the current count, so the badge is right before anything changes.
            hub.sendTo(s -> s.emitter() == emitter, "approvals", Map.of("pending", runService.findAwaitingApproval().size()));
        }
        return emitter;
    }
}
