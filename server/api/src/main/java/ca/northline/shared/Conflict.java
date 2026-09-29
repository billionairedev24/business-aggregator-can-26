package ca.northline.shared;

import lombok.Getter;

/**
 * The request conflicts with the current state (quote already sent, slot taken, stale version). Rendered as HTTP 409
 * ProblemDetail with {@code code} so the client can branch on it.
 */
@Getter
public final class Conflict extends RuntimeException {
    private final String code;

    public Conflict(String code, String message) {
        super(message);
        this.code = code;
    }
}
