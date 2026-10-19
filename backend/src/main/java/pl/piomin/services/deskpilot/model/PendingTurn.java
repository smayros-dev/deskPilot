package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * Serialized working state of a run paused for approval; see {@code TurnStore}.
 */
@Entity
@Table(name = "pending_turn")
public class PendingTurn {

    @Id
    @Column(length = 36)
    private String runId;

    @Column(nullable = false, length = 36, unique = true)
    private String sessionId;

    @Column(nullable = false, columnDefinition = "text")
    private String state;

    @Column(nullable = false)
    private Instant createdAt;

    protected PendingTurn() {
    }

    public PendingTurn(String runId, String sessionId, String state) {
        this.runId = runId;
        this.sessionId = sessionId;
        this.state = state;
        this.createdAt = Instant.now();
    }

    public String getRunId() {
        return runId;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getState() {
        return state;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
