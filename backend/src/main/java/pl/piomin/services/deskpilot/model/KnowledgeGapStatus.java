package pl.piomin.services.deskpilot.model;

/** Lifecycle of a {@link KnowledgeGap}: drafted (by the model or a reviewer), then published as an article or dismissed. */
public enum KnowledgeGapStatus {
    OPEN, DRAFTED, PUBLISHED, DISMISSED;

    public boolean isPending() {
        return this == OPEN || this == DRAFTED;
    }
}
