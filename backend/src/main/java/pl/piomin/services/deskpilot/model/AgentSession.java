package pl.piomin.services.deskpilot.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.DynamicUpdate;

/**
 * A conversation between one user and the agent. {@code busySince} is set while a request runs in it,
 * so only one request per session runs at a time.
 */
@Entity
// Only changed columns are written: busySince is updated by separate claim/release queries during a request.
@DynamicUpdate
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

    /** The first question until the user renames the conversation. */
    @Column(length = 200)
    private String title;

    private Instant lastActivityAt;

    protected AgentSession() {
    }

    public AgentSession(String userId) {
        this.id = UUID.randomUUID().toString();
        this.userId = userId;
        this.createdAt = Instant.now();
        this.lastActivityAt = this.createdAt;
    }

    /** A new message: the first one also names the conversation. */
    public void recordMessage(String message, Instant at) {
        if (title == null) {
            title = titleFrom(message);
        }
        lastActivityAt = at;
    }

    public void rename(String newTitle) {
        this.title = newTitle.strip();
    }

    /** The question shortened at a word boundary, so the list stays readable. */
    static String titleFrom(String message) {
        String oneLine = message.strip().replaceAll("\\s+", " ");
        if (oneLine.length() <= 80) {
            return oneLine;
        }
        int cut = oneLine.lastIndexOf(' ', 80);
        return oneLine.substring(0, cut > 40 ? cut : 80) + "…";
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

    public String getTitle() {
        return title;
    }

    public Instant getLastActivityAt() {
        return lastActivityAt;
    }
}
