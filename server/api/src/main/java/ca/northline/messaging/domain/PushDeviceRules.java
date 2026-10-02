package ca.northline.messaging.domain;

import ca.northline.shared.RuleViolation;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * The device registry's rules (S-102, {@code messaging.push_devices}): which apps, platforms and permission states
 * exist, and the messages of the {@code PUT /api/v1/me/devices/{installationId}} checks.
 */
public final class PushDeviceRules {

    public static final String PLATFORMS = "ios|android";
    public static final String PERMISSIONS = "granted|provisional|denied|undetermined";
    public static final String LANGUAGES = "(?i)(en|fr)(-CA)?";
    public static final int TOKEN_MIN = 16;
    public static final int TOKEN_MAX = 4096;
    public static final int VERSION_MAX = 32;

    public static final String INSTALLATION = "Use 16 to 64 letters, digits, - or _ for the installation id.";
    public static final String PLATFORM = "Choose ios or android.";
    public static final String PERMISSION = "Choose granted, provisional, denied or undetermined.";
    public static final String LANGUAGE = "Choose en or fr.";
    public static final String TOKEN = "Send the push token the platform gave this app.";
    public static final String TOKEN_NEEDED = "A device that allows notifications needs its push token.";
    public static final String VERSION = "Send the app version (at most 32 characters).";

    private static final Pattern INSTALLATION_ID = Pattern.compile("[A-Za-z0-9_-]{16,64}");

    private PushDeviceRules() {}

    /** The app a DPoP-bound token belongs to: the courier app's tokens carry scope {@code courier}. */
    public enum App {
        CONSUMER,
        COURIER;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }

        public static App ofScopes(java.util.Set<String> scopes) {
            return scopes.contains("courier") ? COURIER : CONSUMER;
        }
    }

    public static String installationId(String value) {
        if (!INSTALLATION_ID.matcher(value).matches()) {
            throw RuleViolation.of("installationId", "format", INSTALLATION);
        }
        return value;
    }

    /** {@code en}, {@code fr}, {@code en-CA}, {@code fr-ca} … → {@code en-CA} / {@code fr-CA}. */
    public static String locale(String value) {
        return value.strip().toLowerCase(Locale.ROOT).startsWith("fr") ? "fr-CA" : "en-CA";
    }

    /** Notifications allowed ({@code granted}, or iOS's quiet {@code provisional}) need the token to reach them. */
    public static boolean allowed(String permission) {
        return permission.equals("granted") || permission.equals("provisional");
    }
}
