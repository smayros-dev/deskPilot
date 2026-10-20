package pl.piomin.services.deskpilot.controller;

import java.io.IOException;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;
import pl.piomin.services.deskpilot.dto.KnowledgeDocumentResponse;
import pl.piomin.services.deskpilot.service.DocumentIngestionService;

/** Documents uploaded to the knowledge base (admins only, see SecurityConfig). */
@RestController
@RequestMapping("/api/knowledge-base/documents")
public class KnowledgeDocumentController {

    private final DocumentIngestionService ingestionService;

    public KnowledgeDocumentController(DocumentIngestionService ingestionService) {
        this.ingestionService = ingestionService;
    }

    /** Stores the file and indexes it in the background; poll the document for its status. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocumentResponse upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "title", required = false) String title,
                                            Authentication authentication) throws IOException {
        return KnowledgeDocumentResponse.of(ingestionService.upload(file.getBytes(), file.getOriginalFilename(), title,
                authentication.getName()));
    }

    @GetMapping
    public List<KnowledgeDocumentResponse> list() {
        return ingestionService.list().stream().map(KnowledgeDocumentResponse::of).toList();
    }

    @GetMapping("/{id}")
    public KnowledgeDocumentResponse get(@PathVariable long id) {
        return KnowledgeDocumentResponse.of(ingestionService.get(id));
    }

    @PostMapping("/{id}/reindex")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocumentResponse reindex(@PathVariable long id) {
        return KnowledgeDocumentResponse.of(ingestionService.reindex(id));
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id) {
        ingestionService.delete(id);
    }
}
