package pl.piomin.services.agentai.model;

/** Kind of entry in a run trace. */
public enum StepType {
    USER_INPUT, THOUGHT, TOOL_CALL, TOOL_RESULT, APPROVAL_REQUESTED, APPROVAL_DECISION, ANSWER, GUARDRAIL, ERROR
}
