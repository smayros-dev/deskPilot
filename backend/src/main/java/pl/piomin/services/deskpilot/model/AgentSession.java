package pl.piomin.services.deskpilot.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A conversation between one user and the agent. {@code busySince} is set while a request runs in it,
 * so only one request per session runs at a time.
 */
@Entity
@Table(name = "agent_session")
public class AgentSession {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant busySince;

    protected AgentSession() {
    }

    public AgentSession(String userId) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.createdAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getBusySince() {
        return busySince;
    }
}
