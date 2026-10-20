package pl.piomin.services.deskpilot.model;

import java.time.Instant;
import java.util.Set;

import jakarta.persistence.Column;
import jakarta.persistence.Convert;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/**
 * Runtime configuration of the agent, edited from the UI. A single row ({@link #SINGLETON_ID});
 * defaults come from application.yml the first time it is read.
 */
@Entity
@Table(name = "agent_settings")
public class AgentSettings {

    public static final int SINGLETON_ID = 1;

    @Id
    private Integer id;

    @Column(nullable = false, length = 100)
    private String chatModel;

    private double temperature;

    // Explicit names: the naming strategy maps a trailing capital to "topp" / "rag_topk".
    @Column(name = "top_p")
    private double topP;

    private int contextWindow;
    private int maxOutputTokens;
    private boolean thinking;
    private int maxIterations;

    @Column(name = "rag_top_k")
    private int ragTopK;

    private double ragSimilarityThreshold;

    /** Remind the model to search when it answers without any tool call. */
    private boolean groundingCheck;

    /** Combine keyword and semantic ranking in knowledge-base searches. */
    private boolean hybridSearch;

    /** Check answers and ticket requests against the retrieved articles (one extra model call each). */
    private boolean selfCheck;

    /** Model that reads screenshots; null when screenshots are off. */
    @Column(length = 100)
    private String visionModel;

    /** Provider of the chat model: 'ollama' (local) or a cloud provider such as 'mistral' or 'gemini'. */
    @Column(nullable = false, length = 30)
    private String chatProvider;

    @Column(length = 30)
    private String visionProvider;

    @Convert(converter = StringSetConverter.class)
    @Column(nullable = false, length = 1000)
    private Set<String> disabledTools;

    @Convert(converter = StringSetConverter.class)
    @Column(nullable = false, length = 1000)
    private Set<String> approvalTools;

    /** Replaces the built-in system prompt when set. */
    @Column(columnDefinition = "text")
    private String systemPrompt;

    @Column(nullable = false)
    private Instant updatedAt;

    @Version
    private long version;

    protected AgentSettings() {
    }

    public AgentSettings(Values values) {
        this.id = SINGLETON_ID;
        apply(values);
    }

    /** All editable values at once, so the entity is never half-updated. */
    public record Values(String chatModel, double temperature, double topP, int contextWindow, int maxOutputTokens,
                         boolean thinking, int maxIterations, int ragTopK, double ragSimilarityThreshold,
                         boolean groundingCheck, boolean hybridSearch, boolean selfCheck, String visionModel, String chatProvider, String visionProvider,
                         Set<String> disabledTools, Set<String> approvalTools, String systemPrompt) {
    }

    public void apply(Values values) {
        this.chatModel = values.chatModel();
        this.temperature = values.temperature();
        this.topP = values.topP();
        this.contextWindow = values.contextWindow();
        this.maxOutputTokens = values.maxOutputTokens();
        this.thinking = values.thinking();
        this.maxIterations = values.maxIterations();
        this.ragTopK = values.ragTopK();
        this.ragSimilarityThreshold = values.ragSimilarityThreshold();
        this.groundingCheck = values.groundingCheck();
        this.hybridSearch = values.hybridSearch();
        this.selfCheck = values.selfCheck();
        this.visionModel = values.visionModel() == null || values.visionModel().isBlank() ? null : values.visionModel().strip();
        this.chatProvider = values.chatProvider() == null || values.chatProvider().isBlank() ? "ollama" : values.chatProvider();
        this.visionProvider = this.visionModel == null ? null
                : values.visionProvider() == null || values.visionProvider().isBlank() ? "ollama" : values.visionProvider();
        this.disabledTools = Set.copyOf(values.disabledTools());
        this.approvalTools = Set.copyOf(values.approvalTools());
        this.systemPrompt = values.systemPrompt() == null || values.systemPrompt().isBlank() ? null : values.systemPrompt();
        this.updatedAt = Instant.now();
    }

    public Values values() {
        return new Values(chatModel, temperature, topP, contextWindow, maxOutputTokens, thinking, maxIterations,
                ragTopK, ragSimilarityThreshold, groundingCheck, hybridSearch, selfCheck, visionModel, chatProvider, visionProvider, disabledTools, approvalTools,
                systemPrompt);
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public long getVersion() {
        return version;
    }
}
