package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * A topic users asked about that the knowledge base does not answer (12d): similar unanswered questions grouped
 * together, with the article a reviewer is preparing for it.
 */
@Entity
@Table(name = "knowledge_gap")
public class KnowledgeGap {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    /** The most representative question of the group. */
    @Column(nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private KnowledgeGapStatus status;

    @Column(length = 200)
    private String draftTitle;

    @Column(columnDefinition = "text")
    private String draftContent;

    @Column(length = 300)
    private String draftTags;

    /** The model that wrote the draft, e.g. "ollama:qwen3:8b", or the reviewer who wrote it. */
    @Column(length = 150)
    private String draftedBy;

    /** The article published from this gap. */
    private Long articleId;

    @Column(nullable = false)
    private Instant detectedAt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Column(length = 64)
    private String decidedBy;

    private Instant decidedAt;

    protected KnowledgeGap() {
    }

    public KnowledgeGap(String title) {
        this.title = title.length() > 200 ? title.substring(0, 199) + "…" : title;
        this.status = KnowledgeGapStatus.OPEN;
        this.detectedAt = Instant.now();
        this.updatedAt = detectedAt;
    }

    public void draft(String title, String content, String tags, String draftedBy) {
        this.draftTitle = title;
        this.draftContent = content;
        this.draftTags = tags;
        this.draftedBy = draftedBy;
        this.status = KnowledgeGapStatus.DRAFTED;
        this.updatedAt = Instant.now();
    }

    public void publish(long articleId, String actor) {
        this.articleId = articleId;
        decide(KnowledgeGapStatus.PUBLISHED, actor);
    }

    public void dismiss(String actor) {
        decide(KnowledgeGapStatus.DISMISSED, actor);
    }

    /** New questions joined the group. */
    public void touch() {
        this.updatedAt = Instant.now();
    }

    private void decide(KnowledgeGapStatus status, String actor) {
        this.status = status;
        this.decidedBy = actor;
        this.decidedAt = Instant.now();
        this.updatedAt = decidedAt;
    }

    public Long getId() {
        return id;
    }

    public String getTitle() {
        return title;
    }

    public KnowledgeGapStatus getStatus() {
        return status;
    }

    public String getDraftTitle() {
        return draftTitle;
    }

    public String getDraftContent() {
        return draftContent;
    }

    public String getDraftTags() {
        return draftTags;
    }

    public String getDraftedBy() {
        return draftedBy;
    }

    public Long getArticleId() {
        return articleId;
    }

    public Instant getDetectedAt() {
        return detectedAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getDecidedBy() {
        return decidedBy;
    }

    public Instant getDecidedAt() {
        return decidedAt;
    }
}
