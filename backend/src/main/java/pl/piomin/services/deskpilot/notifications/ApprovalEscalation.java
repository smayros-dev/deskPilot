package pl.piomin.services.deskpilot.notifications;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Marks a run whose approval SLA escalation was sent. */
@Entity
@Table(name = "approval_escalation")
public class ApprovalEscalation {

    @Id
    @Column(length = 36)
    private String runId;

    @Column(nullable = false)
    private Instant notifiedAt;

    protected ApprovalEscalation() {
    }

    public ApprovalEscalation(String runId) {
        this.runId = runId;
        this.notifiedAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public Instant getNotifiedAt() {
        return notifiedAt;
    }
}
