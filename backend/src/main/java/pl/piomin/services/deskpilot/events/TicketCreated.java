package pl.piomin.services.deskpilot.events;

/** A ticket was created (after a reviewer approved it). Delivered to the browser after commit. */
public record TicketCreated(long ticketId) {
}
