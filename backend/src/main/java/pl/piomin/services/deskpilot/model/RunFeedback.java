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

/** A user's 👍 / 👎 on the answer of one of their runs, with an optional comment. */
@Entity
@Table(name = "run_feedback")
public class RunFeedback {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36, unique = true)
    private String runId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 10)
    private FeedbackRating rating;

    @Column(columnDefinition = "text")
    private String comment;

    @Column(nullable = false)
    private Instant createdAt;

    @Column(nullable = false)
    private Instant updatedAt;

    protected RunFeedback() {
    }

    public RunFeedback(String runId, String userId, FeedbackRating rating, String comment) {
        this.runId = runId;
        this.userId = userId;
        this.rating = rating;
        this.comment = comment;
        this.createdAt = Instant.now();
        this.updatedAt = this.createdAt;
    }

    public void change(FeedbackRating rating, String comment) {
        this.rating = rating;
        this.comment = comment;
        this.updatedAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public String getUserId() {
        return userId;
    }

    public FeedbackRating getRating() {
        return rating;
    }

    public String getComment() {
        return comment;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }
}
