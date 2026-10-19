package pl.piomin.services.deskpilot.controller;

import java.util.List;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.ArticleResponse;
import pl.piomin.services.deskpilot.dto.MemoryResponse;
import pl.piomin.services.deskpilot.dto.TicketResponse;
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

    @GetMapping("/tickets")
    public List<TicketResponse> tickets() {
        return ticketService.findAll().stream().map(TicketResponse::of).toList();
    }

    @GetMapping("/tickets/{id}")
    public TicketResponse ticket(@PathVariable long id) {
        return TicketResponse.of(ticketService.get(id));
    }

    @GetMapping("/users/{userId}/memories")
    public List<MemoryResponse> memories(@PathVariable String userId) {
        return memoryService.findAll(userId).stream().map(MemoryResponse::of).toList();
    }

    @GetMapping("/knowledge-base/articles")
    public List<ArticleResponse> articles() {
        return knowledgeBaseService.findAll().stream().map(ArticleResponse::of).toList();
    }
}
