package pl.piomin.services.deskpilot.model;

/** Lifecycle of an agent run. */
public enum RunStatus {
    RUNNING, AWAITING_APPROVAL, COMPLETED, ESCALATED, FAILED
}
