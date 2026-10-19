package pl.piomin.services.deskpilot.exception;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;
import pl.piomin.services.deskpilot.guardrail.GuardrailViolationException;

/**
 * Maps exceptions to RFC 9457 Problem Details. Validation errors (400) are handled by the base class.
 */
@RestControllerAdvice
public class GlobalExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger LOG = LoggerFactory.getLogger(GlobalExceptionHandler.class);

    @ExceptionHandler(NotFoundException.class)
    ProblemDetail handleNotFound(NotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "Not found", e.getMessage());
    }

    @ExceptionHandler(ConflictException.class)
    ProblemDetail handleConflict(ConflictException e) {
        return problem(HttpStatus.CONFLICT, "Conflict", e.getMessage());
    }

    @ExceptionHandler(GuardrailViolationException.class)
    ProblemDetail handleGuardrail(GuardrailViolationException e) {
        ProblemDetail problem = problem(HttpStatus.UNPROCESSABLE_CONTENT, "Message rejected", e.getMessage());
        problem.setProperty("rule", e.getRule());
        return problem;
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ProblemDetail handleIllegalArgument(IllegalArgumentException e) {
        return problem(HttpStatus.BAD_REQUEST, "Bad request", e.getMessage());
    }

    /** The model server (Ollama) could not be reached or timed out. */
    @ExceptionHandler(ResourceAccessException.class)
    ProblemDetail handleModelUnavailable(ResourceAccessException e) {
        LOG.error("Model server unavailable", e);
        return problem(HttpStatus.BAD_GATEWAY, "Model unavailable",
                "The language model server is not reachable. Check that Ollama is running.");
    }

    /** The same mapping for failures that happen after a response was started, e.g. in a Server-Sent Events stream. */
    public ProblemDetail toProblem(RuntimeException e) {
        return switch (e) {
            case NotFoundException n -> handleNotFound(n);
            case ConflictException c -> handleConflict(c);
            case GuardrailViolationException g -> handleGuardrail(g);
            case IllegalArgumentException i -> handleIllegalArgument(i);
            case ResourceAccessException r -> handleModelUnavailable(r);
            default -> {
                LOG.error("Request failed", e);
                yield problem(HttpStatus.INTERNAL_SERVER_ERROR, "Internal error", "The request failed unexpectedly.");
            }
        };
    }

    private static ProblemDetail problem(HttpStatus status, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        return problem;
    }
}
