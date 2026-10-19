package pl.piomin.services.deskpilot.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One run of the scenario catalogue against a model. Counters and status are updated with single-statement queries
 * (see {@code EvaluationRepository}) because the runner and a cancel request may touch the row at the same time.
 */
@Entity
@Table(name = "evaluation")
public class Evaluation {

    @Id
    @Column(length = 36)
    private String id;

    @Column(nullable = false, length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private EvaluationStatus status;

    private int scenarioCount;
    private int completed;
    private int passed;

    @Column(nullable = false, length = 64)
    private String startedBy;

    @Column(nullable = false)
    private Instant startedAt;

    private Instant finishedAt;

    @Column(columnDefinition = "text")
    private String error;

    @Version
    private long version;

    protected Evaluation() {
    }

    public Evaluation(String model, int scenarioCount, String startedBy) {
        this.id = UUID.randomUUID().toString();
        this.model = model;
        this.status = EvaluationStatus.RUNNING;
        this.scenarioCount = scenarioCount;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
    }

    public String getId() {
        return id;
    }

    public String getModel() {
        return model;
    }

    public EvaluationStatus getStatus() {
        return status;
    }

    public int getScenarioCount() {
        return scenarioCount;
    }

    public int getCompleted() {
        return completed;
    }

    public int getPassed() {
        return passed;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public Instant getFinishedAt() {
        return finishedAt;
    }

    public String getError() {
        return error;
    }
}
