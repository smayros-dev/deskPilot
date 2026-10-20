package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;

import pl.piomin.services.deskpilot.model.GapReason;
import pl.piomin.services.deskpilot.model.KnowledgeGap;
import pl.piomin.services.deskpilot.model.KnowledgeGapQuestion;
import pl.piomin.services.deskpilot.model.KnowledgeGapStatus;
import pl.piomin.services.deskpilot.service.KnowledgeGapService;

/** A knowledge gap with its most recent questions (at most {@value #SAMPLES}) and why they were unanswered. */
public record KnowledgeGapResponse(
        long id,
        String title,
        KnowledgeGapStatus status,
        int questionCount,
        Map<GapReason, Long> reasons,
        List<Question> questions,
        String draftTitle,
        String draftContent,
        String draftTags,
        String draftedBy,
        Long articleId,
        Instant detectedAt,
        Instant updatedAt,
        String decidedBy,
        Instant decidedAt) {

    static final int SAMPLES = 5;

    public record Question(String runId, String question, GapReason reason, Instant askedAt) {
    }

    public static KnowledgeGapResponse of(KnowledgeGapService.GapView view) {
        KnowledgeGap gap = view.gap();
        Map<GapReason, Long> reasons = new EnumMap<>(GapReason.class);
        for (KnowledgeGapQuestion q : view.questions()) {
            reasons.merge(q.getReason(), 1L, Long::sum);
        }
        List<Question> samples = view.questions().stream().limit(SAMPLES)
                .map(q -> new Question(q.getRunId(), q.getQuestion(), q.getReason(), q.getAskedAt()))
                .toList();
        return new KnowledgeGapResponse(gap.getId(), gap.getTitle(), gap.getStatus(), view.questions().size(), reasons,
                samples, gap.getDraftTitle(), gap.getDraftContent(), gap.getDraftTags(), gap.getDraftedBy(),
                gap.getArticleId(), gap.getDetectedAt(), gap.getUpdatedAt(), gap.getDecidedBy(), gap.getDecidedAt());
    }
}
