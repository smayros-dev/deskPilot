package pl.piomin.services.deskpilot.controller;

import java.nio.charset.StandardCharsets;
import java.time.Instant;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.AuditEventResponse;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.service.AuditService;

/** The audit trail, newest first (admins only, see SecurityConfig). */
@RestController
@RequestMapping("/api/audit")
public class AuditController {

    static final int MAX_PAGE_SIZE = 200;

    private final AuditService auditService;

    public AuditController(AuditService auditService) {
        this.auditService = auditService;
    }

    /** @param from inclusive, {@code to} exclusive; ISO-8601 instants such as {@code 2026-10-20T00:00:00Z} */
    @GetMapping
    public AuditEventResponse.PageResponse search(@RequestParam(required = false) AuditAction action,
                                                  @RequestParam(required = false) String actor,
                                                  @RequestParam(required = false) Instant from,
                                                  @RequestParam(required = false) Instant to,
                                                  @RequestParam(defaultValue = "0") int page,
                                                  @RequestParam(defaultValue = "50") int size) {
        return AuditEventResponse.PageResponse.of(auditService.search(new AuditService.Filter(action, actor, from, to),
                Math.max(page, 0), Math.clamp(size, 1, MAX_PAGE_SIZE)));
    }

    @GetMapping("/export")
    public ResponseEntity<String> export(@RequestParam(required = false) AuditAction action,
                                         @RequestParam(required = false) String actor,
                                         @RequestParam(required = false) Instant from,
                                         @RequestParam(required = false) Instant to) {
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"deskpilot-audit.csv\"")
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(auditService.exportCsv(new AuditService.Filter(action, actor, from, to)));
    }
}
