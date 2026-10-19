package pl.piomin.services.deskpilot.service;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.repository.TicketRepository;

@Service
@Transactional(readOnly = true)
public class TicketService {

    private final TicketRepository ticketRepository;

    public TicketService(TicketRepository ticketRepository) {
        this.ticketRepository = ticketRepository;
    }

    @Transactional
    public Ticket create(String title, String description, TicketPriority priority, String requestedBy) {
        return ticketRepository.save(new Ticket(title, description, priority, requestedBy));
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
