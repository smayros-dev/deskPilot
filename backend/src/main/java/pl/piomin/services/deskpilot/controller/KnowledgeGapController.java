package pl.piomin.services.deskpilot.controller;

import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import pl.piomin.services.deskpilot.dto.KnowledgeGapResponse;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.service.AuditService;
import pl.piomin.services.deskpilot.service.KnowledgeGapAudit;
import pl.piomin.services.deskpilot.service.KnowledgeGapService;

/** Knowledge gaps (reviewers, 12d): unanswered questions grouped by topic, turned into articles or dismissed. */
@RestController
@RequestMapping("/api/knowledge-gaps")
public class KnowledgeGapController {

    private static final String TARGET = "knowledge-gap";

    private final KnowledgeGapService gapService;
    private final AuditService auditService;

    public KnowledgeGapController(KnowledgeGapService gapService, AuditService auditService) {
        this.gapService = gapService;
        this.auditService = auditService;
    }

    public record DetectionResponse(int candidates, int covered, int newGaps, int grownGaps, int unassigned) {
    }

    public record DraftRequest(
            @NotBlank @Size(max = 200) String title,
            @NotBlank @Size(max = 20_000) String content,
            @Size(max = 300) String tags) {
    }

    @GetMapping
    public List<KnowledgeGapResponse> list() {
        return gapService.list().stream().map(KnowledgeGapResponse::of).toList();
    }

    @PostMapping("/detect")
    public DetectionResponse detect(Authentication authentication) {
        KnowledgeGapService.Detection d = gapService.detect();
        auditService.record(authentication.getName(), AuditAction.KNOWLEDGE_GAPS_DETECTED, TARGET, null, KnowledgeGapAudit.describe(d));
        return new DetectionResponse(d.candidates(), d.covered(), d.newGaps(), d.grownGaps(), d.unassigned());
    }

    /** The model drafts an article for the gap; slow on CPU (it waits for the model). */
    @PostMapping("/{id}/draft")
    public KnowledgeGapResponse draft(@PathVariable long id, Authentication authentication) {
        KnowledgeGapResponse gap = KnowledgeGapResponse.of(gapService.draft(id));
        auditService.record(authentication.getName(), AuditAction.KNOWLEDGE_GAP_DRAFTED, TARGET, String.valueOf(id),
                gap.title() + " (by " + gap.draftedBy() + ")");
        return gap;
    }

    @PutMapping("/{id}/draft")
    public KnowledgeGapResponse updateDraft(@PathVariable long id, @Valid @RequestBody DraftRequest request,
                                            Authentication authentication) {
        return KnowledgeGapResponse.of(gapService.updateDraft(id, request.title(), request.content(), request.tags(),
                authentication.getName()));
    }

    @PostMapping("/{id}/publish")
    public KnowledgeGapResponse publish(@PathVariable long id, Authentication authentication) {
        KnowledgeGapResponse gap = KnowledgeGapResponse.of(gapService.publish(id, authentication.getName()));
        auditService.record(authentication.getName(), AuditAction.KNOWLEDGE_GAP_PUBLISHED, TARGET, String.valueOf(id),
                "Article " + gap.articleId() + ": " + gap.draftTitle());
        return gap;
    }

    @PostMapping("/{id}/dismiss")
    public KnowledgeGapResponse dismiss(@PathVariable long id, Authentication authentication) {
        KnowledgeGapResponse gap = KnowledgeGapResponse.of(gapService.dismiss(id, authentication.getName()));
        auditService.record(authentication.getName(), AuditAction.KNOWLEDGE_GAP_DISMISSED, TARGET, String.valueOf(id), gap.title());
        return gap;
    }
}
