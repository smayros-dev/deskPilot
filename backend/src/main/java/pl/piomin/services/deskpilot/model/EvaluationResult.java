package pl.piomin.services.deskpilot.model;

import java.time.Instant;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The outcome of one scenario in an evaluation. */
@Entity
@Table(name = "evaluation_result")
public class EvaluationResult {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 36)
    private String evaluationId;

    @Column(nullable = false, length = 64)
    private String scenarioId;

    @Column(nullable = false, length = 32)
    private String category;

    @Column(nullable = false, columnDefinition = "text")
    private String question;

    private boolean passed;

    @Column(nullable = false, length = 20)
    private String expectedOutcome;

    @Column(nullable = false, length = 20)
    private String outcome;

    /** Tools the agent called, comma separated, in order. */
    @Column(nullable = false, length = 500)
    private String toolsUsed;

    /** Reasons the scenario failed, one per line; empty when it passed. */
    @Column(nullable = false, columnDefinition = "text")
    private String failures;

    @Column(columnDefinition = "text")
    private String answer;

    @Column(length = 36)
    private String runId;

    private long latencyMillis;
    private int iterations;
    private long tokens;

    @Column(nullable = false)
    private Instant createdAt;

    protected EvaluationResult() {
    }

    public EvaluationResult(String evaluationId, String scenarioId, String category, String question, boolean passed,
                            String expectedOutcome, String outcome, String toolsUsed, String failures, String answer,
                            String runId, long latencyMillis, int iterations, long tokens) {
        this.evaluationId = evaluationId;
        this.scenarioId = scenarioId;
        this.category = category;
        this.question = question;
        this.passed = passed;
        this.expectedOutcome = expectedOutcome;
        this.outcome = outcome;
        this.toolsUsed = toolsUsed;
        this.failures = failures;
        this.answer = answer;
        this.runId = runId;
        this.latencyMillis = latencyMillis;
        this.iterations = iterations;
        this.tokens = tokens;
        this.createdAt = Instant.now();
    }

    public Long getId() {
        return id;
    }

    public String getEvaluationId() {
        return evaluationId;
    }

    public String getScenarioId() {
        return scenarioId;
    }

    public String getCategory() {
        return category;
    }

    public String getQuestion() {
        return question;
    }

    public boolean isPassed() {
        return passed;
    }

    public String getExpectedOutcome() {
        return expectedOutcome;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getToolsUsed() {
        return toolsUsed;
    }

    public String getFailures() {
        return failures;
    }

    public String getAnswer() {
        return answer;
    }

    public String getRunId() {
        return runId;
    }

    public long getLatencyMillis() {
        return latencyMillis;
    }

    public int getIterations() {
        return iterations;
    }

    public long getTokens() {
        return tokens;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }
}
