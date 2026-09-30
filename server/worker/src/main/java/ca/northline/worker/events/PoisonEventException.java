package ca.northline.worker.events;

/**
 * A record no retry can fix: missing envelope headers, not JSON, an unknown type or version, or a payload that breaks
 * its schema. {@code @RetryableTopic(exclude = PoisonEventException.class)} sends it straight to the {@code .dlq}.
 */
public class PoisonEventException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public PoisonEventException(String message) {
        super(message);
    }
}
