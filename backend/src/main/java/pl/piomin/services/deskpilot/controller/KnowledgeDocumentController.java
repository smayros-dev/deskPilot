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
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.KnowledgeDocument;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.DocumentIngestionService;

/** Documents uploaded to the knowledge base (admins only, see SecurityConfig). */
@RestController
@RequestMapping("/api/knowledge-base/documents")
public class KnowledgeDocumentController {

    private final DocumentIngestionService ingestionService;
    private final AuditService auditService;

    public KnowledgeDocumentController(DocumentIngestionService ingestionService, AuditService auditService) {
        this.ingestionService = ingestionService;
        this.auditService = auditService;
    }

    /** Stores the file and indexes it in the background; poll the document for its status. */
    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.ACCEPTED)
    public KnowledgeDocumentResponse upload(@RequestParam("file") MultipartFile file,
                                            @RequestParam(value = "title", required = false) String title,
                                            Authentication authentication) throws IOException {
        KnowledgeDocument document = ingestionService.upload(file.getBytes(), file.getOriginalFilename(), title,
                authentication.getName());
        auditService.record(authentication.getName(), AuditAction.DOCUMENT_UPLOADED, "document", String.valueOf(document.getId()),
                document.getTitle() + " (" + document.getFileName() + ", " + document.getSizeBytes() + " bytes)");
        return KnowledgeDocumentResponse.of(document);
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
    public KnowledgeDocumentResponse reindex(@PathVariable long id, Authentication authentication) {
        KnowledgeDocument document = ingestionService.reindex(id);
        auditService.record(authentication.getName(), AuditAction.DOCUMENT_REINDEXED, "document", String.valueOf(id),
                document.getTitle());
        return KnowledgeDocumentResponse.of(document);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable long id, Authentication authentication) {
        String title = ingestionService.get(id).getTitle();
        ingestionService.delete(id);
        auditService.record(authentication.getName(), AuditAction.DOCUMENT_DELETED, "document", String.valueOf(id), title);
    }
}
