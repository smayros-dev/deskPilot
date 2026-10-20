package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import org.springframework.data.domain.Page;
import pl.piomin.services.deskpilot.model.AuditAction;
import pl.piomin.services.deskpilot.model.AuditEvent;

public record AuditEventResponse(long id, Instant at, String actor, AuditAction action, String targetType, String targetId,
                                 String details) {

    public static AuditEventResponse of(AuditEvent event) {
        return new AuditEventResponse(event.getId(), event.getAt(), event.getActor(), event.getAction(),
                event.getTargetType(), event.getTargetId(), event.getDetails());
    }

    /** One page of the audit trail, newest first. */
    public record PageResponse(List<AuditEventResponse> items, int page, int size, long total, int totalPages) {

        public static PageResponse of(Page<AuditEvent> page) {
            return new PageResponse(page.getContent().stream().map(AuditEventResponse::of).toList(), page.getNumber(),
                    page.getSize(), page.getTotalElements(), page.getTotalPages());
        }
    }
}
