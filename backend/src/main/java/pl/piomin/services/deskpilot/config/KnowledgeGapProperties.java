package pl.piomin.services.deskpilot.config;

import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

/**
 * Knowledge-gap detection ({@code agent.knowledge-gaps.*}, 12d).
 *
 * @param cron                when detection runs (Spring cron; {@code -} turns the schedule off, the manual
 *                            "detect now" still works)
 * @param lookbackDays        how far back unanswered questions are collected
 * @param similarity          cosine similarity from which two questions are the same topic
 * @param minQuestions        questions needed before a new gap is shown to reviewers
 * @param coveredSimilarity   a question this close to an existing article is considered answered by now
 * @param maxQuestions        most recent candidate questions considered per detection
 */
@Validated
@ConfigurationProperties("agent.knowledge-gaps")
public record KnowledgeGapProperties(
        @DefaultValue("0 0 6 * * MON") String cron,
        @DefaultValue("30") @Min(1) int lookbackDays,
        @DefaultValue("0.55") @DecimalMin("0.0") @DecimalMax("1.0") double similarity,
        @DefaultValue("2") @Min(1) int minQuestions,
        @DefaultValue("0.65") @DecimalMin("0.0") @DecimalMax("1.0") double coveredSimilarity,
        @DefaultValue("500") @Min(1) int maxQuestions) {
}
