package ca.northline.worker.notifications;

import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A person to notify — a team member, a customer or a courier: where (email, E.164 mobile), in which language, and
 * their preferences. Read at the moment of sending, never carried in events.
 *
 * @param role the member's role in the business ({@code owner}, {@code bookkeeper}, …), or {@code customer} /
 *     {@code courier}
 * @param locale the language of SMS, email — and push unless {@link #followsApp()}
 * @param followsApp a customer who reads notifications in the app's language (Account › Notifications "Same as
 *     app"): each installation's push is in that installation's language
 */
public record Recipient(
        String userId,
        String role,
        String displayName,
        @Nullable String email,
        @Nullable String phone,
        Locale locale,
        Preferences preferences,
        boolean followsApp) {

    public Recipient(
            String userId,
            String role,
            String displayName,
            @Nullable String email,
            @Nullable String phone,
            Locale locale,
            Preferences preferences) {
        this(userId, role, displayName, email, phone, locale, preferences, false);
    }

    /** {@code en} | {@code fr} for push, or null = each installation's own language. */
    public @Nullable String pushLanguage() {
        return followsApp ? null : language(locale);
    }

    static String language(Locale locale) {
        return "fr".equals(locale.getLanguage()) ? "fr" : "en";
    }
}
