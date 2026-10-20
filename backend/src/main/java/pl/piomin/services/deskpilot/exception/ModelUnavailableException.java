package pl.piomin.services.deskpilot.exception;

/**
 * The model is not called because the circuit breaker is open after several failures in a row; mapped to 503 with a
 * {@code Retry-After} header.
 */
public class ModelUnavailableException extends RuntimeException {

    private final long retryAfterSeconds;

    public ModelUnavailableException(long retryAfterSeconds) {
        super("The language model is unavailable right now after several failed calls. Try again in "
                + retryAfterSeconds + " s.");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long getRetryAfterSeconds() {
        return retryAfterSeconds;
    }
}
