package pl.piomin.services.deskpilot.controller;

import java.util.Map;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionalEventListener;
import pl.piomin.services.deskpilot.dto.TicketResponse;
import pl.piomin.services.deskpilot.events.LiveEventHub;
import pl.piomin.services.deskpilot.events.RunStatusChanged;
import pl.piomin.services.deskpilot.events.TicketCreated;
import pl.piomin.services.deskpilot.model.AgentRun;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.service.RunService;
import pl.piomin.services.deskpilot.service.TicketService;

/**
 * Turns domain events into browser events once the change is committed (so a browser that reloads sees the same data):
 * <ul>
 *     <li>{@code run} — the run, to its owner, when it pauses for approval, resumes or finishes;</li>
 *     <li>{@code approvals} — {@code {"pending": n}}, to reviewers and admins, after any of those changes;</li>
 *     <li>{@code ticket} — the ticket, to its requester and to reviewers and admins, when it is created.</li>
 * </ul>
 */
@Component
class LiveEventRelay {

    private final LiveEventHub hub;
    private final RunService runService;
    private final TicketService ticketService;
    private final RunResponseMapper mapper;

    LiveEventRelay(LiveEventHub hub, RunService runService, TicketService ticketService, RunResponseMapper mapper) {
        this.hub = hub;
        this.runService = runService;
        this.ticketService = ticketService;
        this.mapper = mapper;
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onRunStatusChanged(RunStatusChanged event) {
        AgentRun run = runService.get(event.runId());
        hub.sendToUser(run.getUserId(), "run", mapper.toResponse(run));
        publishApprovalCount();
    }

    @TransactionalEventListener(fallbackExecution = true)
    void onTicketCreated(TicketCreated event) {
        Ticket ticket = ticketService.get(event.ticketId());
        hub.sendTo(s -> s.user().username().equals(ticket.getRequestedBy()) || s.user().canSeeAll(),
                "ticket", TicketResponse.of(ticket));
    }

    private void publishApprovalCount() {
        hub.sendTo(s -> s.user().canSeeAll(), "approvals", Map.of("pending", runService.findAwaitingApproval().size()));
    }
}
