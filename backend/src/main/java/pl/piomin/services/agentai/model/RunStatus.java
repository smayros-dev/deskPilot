package pl.piomin.services.agentai.model;

/** Lifecycle of an agent run. */
public enum RunStatus {
    RUNNING, AWAITING_APPROVAL, COMPLETED, ESCALATED, FAILED
}
