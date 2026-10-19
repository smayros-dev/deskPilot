package pl.piomin.services.deskpilot.dto;

import java.time.Instant;

import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.service.FeedbackService;

/** A rated answer, as listed for reviewers: what was asked, what the agent said, and what the user thought. */
public record FeedbackResponse(String runId, String userId, String model, String question, String answer,
                               FeedbackRating rating, String comment, Instant updatedAt) {

    public static FeedbackResponse of(FeedbackService.Rated rated) {
        return new FeedbackResponse(rated.run().getId(), rated.feedback().getUserId(), rated.run().getModel(),
                rated.run().getUserInput(), rated.run().getFinalAnswer(), rated.feedback().getRating(),
                rated.feedback().getComment(), rated.feedback().getUpdatedAt());
    }
}
