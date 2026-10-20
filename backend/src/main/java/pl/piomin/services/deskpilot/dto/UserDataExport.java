package pl.piomin.services.deskpilot.dto;

import java.time.Instant;
import java.util.List;

import pl.piomin.services.deskpilot.model.FeedbackRating;
import pl.piomin.services.deskpilot.model.RunStatus;
import pl.piomin.services.deskpilot.model.TicketPriority;
import pl.piomin.services.deskpilot.model.TicketStatus;

/** Everything DeskPilot stores about one user ("right of access"), as downloaded from {@code GET /api/me/export}. */
public record UserDataExport(
        String userId,
        Instant exportedAt,
        List<Conversation> conversations,
        List<Memory> memories,
        List<TicketRequested> tickets) {

    public record Conversation(String id, Instant createdAt, List<Run> runs) {
    }

    public record Run(String id, Instant createdAt, RunStatus status, String model, String question, String answer,
                      Feedback feedback) {
    }

    public record Feedback(FeedbackRating rating, String comment, Instant updatedAt) {
    }

    public record Memory(long id, String content, Instant createdAt) {
    }

    public record TicketRequested(long id, String title, String description, TicketPriority priority, TicketStatus status,
                                  Instant createdAt) {
    }
}
