package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A specialist agent as stored in the database and edited on the Specialists page (13g). Keywords, tools and examples
 * are JSON arrays (read by {@code SpecialistCatalog}); a null {@code tools} means every tool.
 */
@Entity
@Table(name = "specialist")
public class SpecialistDefinition {

    @Id
    @Column(length = 40)
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 500)
    private String description;

    @Column(nullable = false, columnDefinition = "text")
    private String keywords;

    @Column(columnDefinition = "text")
    private String tools;

    @Column(nullable = false, columnDefinition = "text")
    private String prompt;

    @Column(nullable = false, columnDefinition = "text")
    private String examples;

    @Column(nullable = false)
    private boolean enabled;

    @Column(nullable = false)
    private int sortOrder;

    @Column(nullable = false, length = 64)
    private String updatedBy;

    @Column(nullable = false)
    private Instant updatedAt;

    protected SpecialistDefinition() {
    }

    public SpecialistDefinition(String id) {
        this.id = id;
    }

    /** Replaces every editable field; the JSON columns are written by the caller. */
    public void update(String name, String description, String keywords, String tools, String prompt, String examples,
                       boolean enabled, int sortOrder, String actor) {
        this.name = name;
        this.description = description;
        this.keywords = keywords;
        this.tools = tools;
        this.prompt = prompt;
        this.examples = examples;
        this.enabled = enabled;
        this.sortOrder = sortOrder;
        this.updatedBy = actor;
        this.updatedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public String getKeywords() {
        return keywords;
    }

    public String getTools() {
        return tools;
    }

    public String getPrompt() {
        return prompt;
    }

    public String getExamples() {
        return examples;
    }

    public boolean isEnabled() {
        return enabled;
    }

    public int getSortOrder() {
        return sortOrder;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
