package pl.piomin.services.deskpilot.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import pl.piomin.services.deskpilot.exception.ConflictException;
import pl.piomin.services.deskpilot.model.AuditAction;

/** Weekly knowledge-gap detection ({@code agent.knowledge-gaps.cron}, Monday 06:00 by default; {@code -} turns it off). */
@Component
class KnowledgeGapScheduler {

    private static final Logger LOG = LoggerFactory.getLogger(KnowledgeGapScheduler.class);

    private final KnowledgeGapService gapService;
    private final AuditService auditService;

    KnowledgeGapScheduler(KnowledgeGapService gapService, AuditService auditService) {
        this.gapService = gapService;
        this.auditService = auditService;
    }

    @Scheduled(cron = "${agent.knowledge-gaps.cron:0 0 6 * * MON}")
    void detect() {
        try {
            KnowledgeGapService.Detection detection = gapService.detect();
            auditService.record(AuditService.SYSTEM, AuditAction.KNOWLEDGE_GAPS_DETECTED, "knowledge-gap", null,
                    KnowledgeGapAudit.describe(detection));
        } catch (ConflictException e) {
            LOG.info("Skipped scheduled knowledge-gap detection: {}", e.getMessage());
        } catch (RuntimeException e) {
            LOG.warn("Scheduled knowledge-gap detection failed", e);
        }
    }
}
