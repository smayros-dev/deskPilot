package pl.piomin.services.agentai.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/** Long-term (semantic) memory: a fact about a user that outlives a single conversation. */
@Entity
@Table(name = "memory_fact", indexes = @Index(name = "idx_memory_fact_user", columnList = "userId"))
public class MemoryFact {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, length = 500)
    private String content;

    @Column(nullable = false)
    private Instant createdAt;

    protected MemoryFact() {
    }

    public MemoryFact(String userId, String content) {
        this.userId = userId;
        this.content = content;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getUserId() {
        return userId;
    }

    public String getContent() {
        return content;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
