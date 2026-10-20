package pl.piomin.services.deskpilot.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/**
 * An A/B experiment (12m): while it runs, each new conversation is assigned to variant A or B (B gets
 * {@link #getBPercent()} percent of them) and its runs use that variant's provider, model and prompt.
 */
@Entity
@Table(name = "experiment")
public class Experiment {

    @Id
    private String id;

    @Column(nullable = false, length = 100)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ExperimentStatus status;

    // Explicit names: the naming strategy maps "aProvider" to "aprovider".
    @Column(name = "a_provider", nullable = false, length = 30)
    private String aProvider;

    @Column(name = "a_model", nullable = false, length = 100)
    private String aModel;

    /** Prompt version of variant A; null = the prompt from the settings. */
    @Column(name = "a_prompt_id")
    private Long aPromptId;

    @Column(name = "b_provider", nullable = false, length = 30)
    private String bProvider;

    @Column(name = "b_model", nullable = false, length = 100)
    private String bModel;

    @Column(name = "b_prompt_id")
    private Long bPromptId;

    @Column(name = "b_percent", nullable = false)
    private int bPercent;

    @Column(nullable = false, length = 64)
    private String startedBy;

    @Column(nullable = false)
    private Instant startedAt;

    @Column(length = 64)
    private String stoppedBy;

    private Instant stoppedAt;

    protected Experiment() {
    }

    public Experiment(String name, Variant a, Variant b, int bPercent, String startedBy) {
        this.id = UUID.randomUUID().toString();
        this.name = name;
        this.status = ExperimentStatus.RUNNING;
        this.aProvider = a.provider();
        this.aModel = a.model();
        this.aPromptId = a.promptId();
        this.bProvider = b.provider();
        this.bModel = b.model();
        this.bPromptId = b.promptId();
        this.bPercent = bPercent;
        this.startedBy = startedBy;
        this.startedAt = Instant.now();
    }

    /** What one arm of the experiment runs with. */
    public record Variant(String provider, String model, Long promptId) {
    }

    public void stop(String actor) {
        this.status = ExperimentStatus.STOPPED;
        this.stoppedBy = actor;
        this.stoppedAt = Instant.now();
    }

    public boolean isRunning() {
        return status == ExperimentStatus.RUNNING;
    }

    public Variant variantA() {
        return new Variant(aProvider, aModel, aPromptId);
    }

    public Variant variantB() {
        return new Variant(bProvider, bModel, bPromptId);
    }

    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public ExperimentStatus getStatus() {
        return status;
    }

    public int getBPercent() {
        return bPercent;
    }

    public String getStartedBy() {
        return startedBy;
    }

    public Instant getStartedAt() {
        return startedAt;
    }

    public String getStoppedBy() {
        return stoppedBy;
    }

    public Instant getStoppedAt() {
        return stoppedAt;
    }
}
