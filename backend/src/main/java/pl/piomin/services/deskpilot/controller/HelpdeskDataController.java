package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.ArticleResponse;
import pl.piomin.services.deskpilot.dto.MemoryResponse;
import pl.piomin.services.deskpilot.dto.TicketResponse;
import pl.piomin.services.deskpilot.exception.NotFoundException;
import pl.piomin.services.deskpilot.model.Ticket;
import pl.piomin.services.deskpilot.security.CurrentUser;
import pl.piomin.services.deskpilot.service.KnowledgeBaseService;
import pl.piomin.services.deskpilot.service.MemoryService;
import pl.piomin.services.deskpilot.service.TicketService;

/** Read-only views of the data the agent works with: tickets, memories and knowledge-base articles. */
@RestController
@RequestMapping("/api")
public class HelpdeskDataController {

    private final TicketService ticketService;
    private final MemoryService memoryService;
    private final KnowledgeBaseService knowledgeBaseService;

    public HelpdeskDataController(TicketService ticketService, MemoryService memoryService,
                                  KnowledgeBaseService knowledgeBaseService) {
        this.ticketService = ticketService;
        this.memoryService = memoryService;
        this.knowledgeBaseService = knowledgeBaseService;
    }

    /** The caller's tickets; reviewers and admins see all of them. */
    @GetMapping("/tickets")
    public List<TicketResponse> tickets(Authentication authentication) {
        CurrentUser user = CurrentUser.of(authentication);
        List<Ticket> tickets = user.canSeeAll() ? ticketService.findAll() : ticketService.findByRequester(user.username());
        return tickets.stream().map(TicketResponse::of).toList();
    }

    @GetMapping("/tickets/{id}")
    public TicketResponse ticket(@PathVariable long id, Authentication authentication) {
        Ticket ticket = ticketService.get(id);
        if (!CurrentUser.of(authentication).canSee(ticket.getRequestedBy())) {
            throw new NotFoundException("Ticket " + id + " not found");
        }
        return TicketResponse.of(ticket);
    }

    /** Long-term memories the agent keeps about the caller. */
    @GetMapping("/me/memories")
    public List<MemoryResponse> memories(Authentication authentication) {
        return memoryService.findAll(authentication.getName()).stream().map(MemoryResponse::of).toList();
    }

    @GetMapping("/knowledge-base/articles")
    public List<ArticleResponse> articles() {
        return knowledgeBaseService.findAll().stream().map(ArticleResponse::of).toList();
    }
}
