package pl.piomin.services.deskpilot.agent.tool;

import java.time.Instant;

import org.springframework.ai.chat.model.ToolContext;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.service.TicketService;

@Component
public class TicketTools implements AgentTools {

    static final int MAX_TITLE_LENGTH = 200;

    private final TicketService ticketService;

    public TicketTools(TicketService ticketService) {
        this.ticketService = ticketService;
    }

    public record TicketView(Long id, String title, String description, String priority, String status,
                             String requestedBy, Instant createdAt) {

        static TicketView of(Ticket ticket) {
            return new TicketView(ticket.getId(), ticket.getTitle(), ticket.getDescription(),
                    ticket.getPriority().name(), ticket.getStatus().name(), ticket.getRequestedBy(), ticket.getCreatedAt());
        }
    }

    @Tool(name = "get_ticket", description = "Look up an existing helpdesk ticket by its numeric id.")
    public TicketView getTicket(@ToolParam(description = "Ticket id, e.g. 42") long ticketId) {
        return TicketView.of(ticketService.get(ticketId));
    }

    @RequiresApproval
    @Tool(name = "create_ticket", description = """
            Create a helpdesk ticket for the current user. Use it only when the knowledge base cannot solve the problem, \
            or for requests that always need a ticket (hardware, software installation, lost MFA device). \
            A human reviews every ticket before it is created.""")
    public TicketView createTicket(
            @ToolParam(description = "Short summary, max 200 characters") String title,
            @ToolParam(description = "Problem description with the steps already tried") String description,
            @ToolParam(description = "One of LOW, MEDIUM, HIGH, CRITICAL") String priority,
            ToolContext toolContext) {
        if (title == null || title.isBlank() || title.length() > MAX_TITLE_LENGTH) {
            throw new IllegalArgumentException("title must be 1-" + MAX_TITLE_LENGTH + " characters");
        }
        if (description == null || description.isBlank()) {
            throw new IllegalArgumentException("description must not be blank");
        }
        TicketPriority ticketPriority = parsePriority(priority);
        String userId = RunContext.from(toolContext).userId();
        return TicketView.of(ticketService.create(title.strip(), description.strip(), ticketPriority, userId));
    }

    private static TicketPriority parsePriority(String priority) {
        try {
            return TicketPriority.valueOf(priority.strip().toUpperCase());
        } catch (RuntimeException e) {
            throw new IllegalArgumentException("priority must be one of LOW, MEDIUM, HIGH, CRITICAL");
        }
    }
}
