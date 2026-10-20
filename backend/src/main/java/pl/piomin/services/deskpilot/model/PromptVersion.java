package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** A named, immutable version of the system prompt, used by experiments and loadable into the settings (12m). */
@Entity
@Table(name = "prompt_version")
public class PromptVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 100, unique = true)
    private String name;

    @Column(nullable = false, columnDefinition = "text")
    private String content;

    @Column(nullable = false, length = 64)
    private String createdBy;

    @Column(nullable = false)
    private Instant createdAt;

    protected PromptVersion() {
    }

    public PromptVersion(String name, String content, String createdBy) {
        this.name = name;
        this.content = content;
        this.createdBy = createdBy;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
