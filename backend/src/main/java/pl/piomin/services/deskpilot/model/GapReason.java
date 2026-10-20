package pl.piomin.services.deskpilot.model;

/** Why a question counts as unanswered by the knowledge base. */
public enum GapReason {
    /** The agent handed the conversation to a human. */
    ESCALATED,
    /** The agent opened a ticket. */
    TICKET,
    /** The user rated the answer 👎. */
    NEGATIVE_FEEDBACK,
    /** Every knowledge-base search of the run came back empty. */
    NOT_FOUND
}
