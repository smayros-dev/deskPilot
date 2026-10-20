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

/** One unanswered question of a {@link KnowledgeGap}; a run belongs to at most one gap, so it is never counted twice. */
@Entity
@Table(name = "knowledge_gap_question")
public class KnowledgeGapQuestion {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false)
    private Long gapId;

    @Column(nullable = false, unique = true)
    private String runId;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GapReason reason;

    @Column(nullable = false)
    private Instant askedAt;

    protected KnowledgeGapQuestion() {
    }

    public KnowledgeGapQuestion(long gapId, String runId, String question, GapReason reason, Instant askedAt) {
        this.gapId = gapId;
        this.runId = runId;
        this.question = question;
        this.reason = reason;
        this.askedAt = askedAt;
    }

    public Long getId() {
        return id;
    }

    public Long getGapId() {
        return gapId;
    }

    public String getRunId() {
        return runId;
    }

    public String getQuestion() {
        return question;
    }

    public GapReason getReason() {
        return reason;
    }

    public Instant getAskedAt() {
        return askedAt;
    }
}
