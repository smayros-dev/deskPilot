package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.model.TicketStatus;

public record TicketResponse(long id, String title, String description, TicketPriority priority, TicketStatus status,
                             String requestedBy, Instant createdAt) {

    public static TicketResponse of(Ticket ticket) {
        return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(), ticket.getPriority(),
                ticket.getStatus(), ticket.getRequestedBy(), ticket.getCreatedAt());
    }
}
