package pl.piomin.services.deskpilot.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import pl.piomin.services.deskpilot.model.FeedbackRating;

/** 👍 / 👎 on an answer, with an optional comment (mostly useful with 👎). */
public record FeedbackRequest(@NotNull FeedbackRating rating, @Size(max = 1000) String comment) {
}
