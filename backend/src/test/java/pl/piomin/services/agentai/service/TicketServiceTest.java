package pl.piomin.services.agentai.service;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import pl.piomin.services.agentai.TestcontainersConfiguration;
import pl.piomin.services.agentai.exception.NotFoundException;
import pl.piomin.services.agentai.model.Ticket;
import pl.piomin.services.agentai.model.TicketPriority;
import pl.piomin.services.agentai.model.TicketStatus;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DataJpaTest
@Import({TestcontainersConfiguration.class, TicketService.class})
class TicketServiceTest {

    @Autowired
    private TicketService ticketService;

    @Test
    void createsOpenTicket() {
        Ticket ticket = ticketService.create("Laptop broken", "Screen flickers", TicketPriority.HIGH, "alice");

        Ticket loaded = ticketService.get(ticket.getId());
        assertThat(loaded.getStatus()).isEqualTo(TicketStatus.OPEN);
        assertThat(loaded.getPriority()).isEqualTo(TicketPriority.HIGH);
        assertThat(loaded.getRequestedBy()).isEqualTo("alice");
    }

    @Test
    void listsNewestFirst() {
        ticketService.create("First", "d", TicketPriority.LOW, "alice");
        ticketService.create("Second", "d", TicketPriority.LOW, "alice");

        assertThat(ticketService.findAll()).extracting(Ticket::getTitle).containsExactly("Second", "First");
    }

    @Test
    void unknownTicketIsNotFound() {
        assertThatThrownBy(() -> ticketService.get(999))
                .isInstanceOf(NotFoundException.class);
    }
}
