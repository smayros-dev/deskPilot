package pl.piomin.services.agentai.repository;

import java.util.List;

import org.springframework.data.jpa.repository.JpaRepository;
import pl.piomin.services.agentai.model.Ticket;

public interface TicketRepository extends JpaRepository<Ticket, Long> {

    List<Ticket> findAllByOrderByCreatedAtDesc();
}
