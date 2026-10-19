package pl.piomin.services.deskpilot.exception;

/**
 * The request is valid but conflicts with the current state, e.g. approving a run that is not paused.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
