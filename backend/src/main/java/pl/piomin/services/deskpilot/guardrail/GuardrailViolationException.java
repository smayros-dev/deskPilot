package pl.piomin.services.deskpilot.guardrail;

/** A user message was rejected before reaching the model. */
public class GuardrailViolationException extends RuntimeException {

    private final String rule;

    public GuardrailViolationException(String rule, String message) {
        super(message);
        this.rule = rule;
    }

    public String getRule() {
        return rule;
    }
}
