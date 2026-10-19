package pl.piomin.services.agentai.model;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Index;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * One user request handled by the agent, from the user message to the final answer (episodic memory).
 * A run can pause in {@link RunStatus#AWAITING_APPROVAL} and resume later.
 */
@Entity
@Table(name = "agent_run", indexes = @Index(name = "idx_agent_run_session", columnList = "sessionId"))
public class AgentRun {

    @Id
    private String id;

    @Column(nullable = false, length = 64)
    private String sessionId;

    @Column(nullable = false, length = 64)
    private String userId;

    @Column(nullable = false, columnDefinition = "text")
    private String userInput;

    /** Chat model that handled the run. */
    @Column(length = 100)
    private String model;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private RunStatus status;

    @Column(columnDefinition = "text")
    private String finalAnswer;

    private int iterations;
    private int toolCalls;
    private int toolErrors;
    private long promptTokens;
    private long completionTokens;

    /** Time spent working (model + tools), excluding time waiting for a human decision. */
    private long activeMillis;

    @Column(nullable = false)
    private Instant createdAt;

    private Instant completedAt;

    @Version
    private long version;

    protected AgentRun() {
    }

    public AgentRun(String sessionId, String userId, String userInput, String model) {
        this.id = UUID.randomUUID().toString();
        this.sessionId = sessionId;
        this.userId = userId;
        this.userInput = userInput;
        this.model = model;
        this.status = RunStatus.RUNNING;
        this.createdAt = Instant.now();
    }

    public void recordModelCall(long promptTokens, long completionTokens) {
        this.iterations++;
        this.promptTokens += promptTokens;
        this.completionTokens += completionTokens;
    }

    public void recordToolCall(boolean error) {
        this.toolCalls++;
        if (error) {
            this.toolErrors++;
        }
    }

    public void addActiveMillis(long millis) {
        this.activeMillis += millis;
    }

    public void pauseForApproval() {
        this.status = RunStatus.AWAITING_APPROVAL;
    }

    public void resume() {
        this.status = RunStatus.RUNNING;
    }

    public void finish(RunStatus finalStatus, String answer) {
        this.status = finalStatus;
        this.finalAnswer = answer;
        this.completedAt = Instant.now();
    }

    public boolean isFinished() {
        return status != RunStatus.RUNNING && status != RunStatus.AWAITING_APPROVAL;
    }

    public String getId() {
        return id;
    }

    public String getSessionId() {
        return sessionId;
    }

    public String getUserId() {
        return userId;
    }

    public String getUserInput() {
        return userInput;
    }

    public String getModel() {
        return model;
    }

    public RunStatus getStatus() {
        return status;
    }

    public String getFinalAnswer() {
        return finalAnswer;
    }

    public int getIterations() {
        return iterations;
    }

    public int getToolCalls() {
        return toolCalls;
    }

    public int getToolErrors() {
        return toolErrors;
    }

    public long getPromptTokens() {
        return promptTokens;
    }

    public long getCompletionTokens() {
        return completionTokens;
    }

    public long getActiveMillis() {
        return activeMillis;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getCompletedAt() {
        return completedAt;
    }
}
