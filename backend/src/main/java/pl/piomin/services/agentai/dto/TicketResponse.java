package pl.piomin.services.agentai.dto;

import java.time.Instant;

import pl.piomin.services.agentai.model.Ticket;
import pl.piomin.services.agentai.model.TicketPriority;
import pl.piomin.services.agentai.model.TicketStatus;

public record TicketResponse(long id, String title, String description, TicketPriority priority, TicketStatus status,
                             String requestedBy, Instant createdAt) {

    public static TicketResponse of(Ticket ticket) {
        return new TicketResponse(ticket.getId(), ticket.getTitle(), ticket.getDescription(), ticket.getPriority(),
                ticket.getStatus(), ticket.getRequestedBy(), ticket.getCreatedAt());
    }
}
