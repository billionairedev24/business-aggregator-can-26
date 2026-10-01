package ca.northline.platform;

import ca.northline.platform.logging.Redactor;
import java.util.Objects;

/**
 * The S-112 log {@link Redactor} for other modules (its package is internal to the platform module): one set of rules
 * for secrets and personal data, whether a string is going to the logs or to an AI model (S-129's prompt redaction).
 */
public final class Redaction {
    private Redaction() {}

    /** {@code text} with secrets, emails, card numbers (last 4 kept), phones, one-time codes and postal codes masked. */
    public static String redact(String text) {
        return Objects.requireNonNull(Redactor.redact(text));
    }
}
