package ca.northline.ai.api;

/** 503 {@code ai_unavailable}: no model configured, the provider unreachable, timed out or failing. */
public final class AiUnavailable extends RuntimeException {

    public static final String CODE = "ai_unavailable";

    public AiUnavailable(String message) {
        super(message);
    }

    public AiUnavailable(String message, Throwable cause) {
        super(message, cause);
    }
}
