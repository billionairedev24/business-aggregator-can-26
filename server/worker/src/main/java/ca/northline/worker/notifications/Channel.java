package ca.northline.worker.notifications;

/** A Settings › Notifications column; {@link #code()} is also the {@code events.processed_events} consumer of claims. */
public enum Channel {
    PUSH("push"),
    SMS("sms"),
    EMAIL("email");

    private final String code;

    Channel(String code) {
        this.code = code;
    }

    public String code() {
        return code;
    }

    /** Quiet hours hold back push and SMS; email is never held (S-13). */
    public boolean quietable() {
        return this != EMAIL;
    }
}
