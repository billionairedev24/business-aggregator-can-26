package ca.northline.email;

import lombok.Getter;

/** The provider did not accept an email. */
@Getter
public final class EmailDeliveryFailed extends RuntimeException {

    /** Whether trying again can help. */
    public enum Kind {
        /**
         * Permanent for this message: invalid or suppressed recipient, rejected content (4xx other than 408/429, SMTP
         * 5xx on the recipient). Retrying sends the same thing to the same answer, so callers log it and move on.
         */
        REJECTED,
        /**
         * The provider or our configuration can't take mail now: 5xx, 408/429 throttling, time-outs, connection
         * refused, bad credentials, unverified sender. Retried; still failing afterwards, the caller keeps the work to
         * try again later.
         */
        UNAVAILABLE
    }

    private final Kind kind;

    public EmailDeliveryFailed(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public EmailDeliveryFailed(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }

    public static EmailDeliveryFailed rejected(String message) {
        return new EmailDeliveryFailed(Kind.REJECTED, message);
    }

    public static EmailDeliveryFailed unavailable(String message, Throwable cause) {
        return new EmailDeliveryFailed(Kind.UNAVAILABLE, message, cause);
    }

    /**
     * HTTP providers: 401/403 (credentials, unverified sender — our configuration) and 408/429 (throttling) are
     * unavailable like 5xx; any other 4xx rejects this message.
     */
    public static Kind ofHttpStatus(int status) {
        return switch (status) {
            case 401, 403, 408, 429 -> Kind.UNAVAILABLE;
            default -> status >= 400 && status < 500 ? Kind.REJECTED : Kind.UNAVAILABLE;
        };
    }
}
