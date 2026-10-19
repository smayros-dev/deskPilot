package pl.piomin.services.deskpilot.eval;

/** How a run ended, as seen by the evaluation. */
public enum Outcome {
    /** The agent answered (run COMPLETED). */
    ANSWERED,
    /** The agent asked for a human approval (a gated tool such as create_ticket). */
    APPROVAL,
    /** The agent handed the request to a human, or ran out of steps. */
    ESCALATED,
    /** The guardrails rejected the message before the agent ran. */
    BLOCKED,
    /** The run failed (empty answer, model error). */
    FAILED
}
