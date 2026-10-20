package pl.piomin.services.deskpilot.service;

import java.util.List;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.events.TicketCreated;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.repository.TicketRepository;

@Service
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;
    private final ApplicationEventPublisher events;

    public TicketService(TicketRepository ticketRepository, ApplicationEventPublisher events) {
        this.ticketRepository = ticketRepository;
        this.events = events;
    }

    @Transactional
    public Ticket create(String title, String description, TicketPriority priority, String requestedBy) {
        Ticket ticket = ticketRepository.save(new Ticket(title, description, priority, requestedBy));
        events.publishEvent(new TicketCreated(ticket.getId()));
        return ticket;
    }

    public Ticket get(long id) {
        return ticketRepository.findById(id)
                .orElseThrow(() -> new NotFoundException("Ticket " + id + " not found"));
    }

    public List<Ticket> findByRequester(String requestedBy) {
        return ticketRepository.findByRequestedByOrderByCreatedAtDesc(requestedBy);
    }

    public List<Ticket> findAll() {
        return ticketRepository.findAllByOrderByCreatedAtDesc();
    }
}
