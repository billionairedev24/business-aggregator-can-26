package ca.northline.auth.domain;

import java.util.Arrays;
import java.util.Locale;
import java.util.Optional;

/**
 * S-154: the app a person creates an account in or resets a password from — decides where the email's link opens and
 * whether a second factor is required right after (business and staff apps) or only offered (the consumer site).
 */
public enum App {
    STUDIO,
    CONSUMER,
    CONSOLE;

    /** {@code studio} — stored in {@code auth.pending_accounts.app} and {@code auth.email_codes.app}. */
    public String code() {
        return name().toLowerCase(Locale.ROOT);
    }

    /** Business and staff accounts need a passkey or an authenticator app before they get in. */
    public boolean requiresSecondFactor() {
        return this != CONSUMER;
    }

    /** Where the footer of an email sends the reader for their settings (EmailContent.settingsPlace). */
    public String settingsPlace() {
        return this == CONSUMER ? "account" : "studio";
    }

    public static Optional<App> of(String code) {
        return Arrays.stream(values()).filter(a -> a.code().equals(code)).findFirst();
    }
}
