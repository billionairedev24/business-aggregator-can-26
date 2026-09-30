package ca.northline.auth.application;

import lombok.Getter;

/** The SMS/voice provider did not accept a code ({@link SmsSender}). The message is for logs, never for users. */
@Getter
public final class SmsDeliveryFailed extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Whose fault. */
    public enum Kind {
        /** The number can't receive this channel: invalid, not a mobile, opted out (STOP), unreachable. */
        UNDELIVERABLE_NUMBER,
        /** The provider is down, throttling, or misconfigured (credentials, sender, country permissions). */
        PROVIDER_UNAVAILABLE
    }

    private final Kind kind;

    public SmsDeliveryFailed(Kind kind, String message) {
        super(message);
        this.kind = kind;
    }

    public SmsDeliveryFailed(Kind kind, String message, Throwable cause) {
        super(message, cause);
        this.kind = kind;
    }
}
