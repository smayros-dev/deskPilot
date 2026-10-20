package pl.piomin.services.deskpilot.events;

/**
 * A chat run was paused for approval, resumed or finished. Published inside the transaction that changed it and
 * delivered to the browser after commit (see {@code LiveEventRelay}). Evaluation runs publish nothing.
 */
public record RunStatusChanged(String runId) {
}
