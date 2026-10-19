package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;

/**
 * One entry of a run trace: a thought, tool call, tool result, approval or answer.
 * References its run by id (no JPA association) so traces can be appended without loading the run.
 */
@Entity
@Table(name = "agent_step", indexes = @Index(name = "idx_agent_step_run", columnList = "runId"))
public class AgentStep {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String runId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private StepType type;

    /** Tool name for tool steps, decision for approvals; null otherwise. */
    @Column(length = 100)
    private String name;

    @Column(columnDefinition = "text")
    private String content;

    private boolean error;

    private long durationMillis;

    @Column(nullable = false)
    private Instant createdAt;

    protected AgentStep() {
    }

    public AgentStep(String runId, StepType type, String name, String content, boolean error, long durationMillis) {
        this.runId = runId;
        this.type = type;
        this.name = name;
        this.content = content;
        this.error = error;
        this.durationMillis = durationMillis;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getRunId() {
        return runId;
    }

    public StepType getType() {
        return type;
    }

    public String getName() {
        return name;
    }

    public String getContent() {
        return content;
    }

    public boolean isError() {
        return error;
    }

    public long getDurationMillis() {
        return durationMillis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
