package ca.northline.identity.domain;

import java.time.LocalDate;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * A person's profile as the Studio shows it ("Signed in as …", the account menu).
 *
 * @param mfaPrimary passkey | totp | sms — the registered second factor
 */
public record UserProfile(
        String id,
        String firstName,
        String lastName,
        @Nullable String email,
        @Nullable String phone,
        String locale,
        LocalDate memberSince,
        @Nullable String mfaPrimary) {

    /**
     * Builds the profile from the stored columns. Rows written before V020 only have {@code display_name}; its first
     * word is the first name and the rest the last name.
     */
    public static UserProfile of(
            String id,
            @Nullable String firstName,
            @Nullable String lastName,
            @Nullable String displayName,
            @Nullable String email,
            @Nullable String phone,
            @Nullable String locale,
            LocalDate memberSince,
            @Nullable String mfaPrimary) {
        var dn = displayName == null ? "" : displayName.trim();
        int space = dn.indexOf(' ');
        var first =
                firstName == null || firstName.isBlank() ? (space < 0 ? dn : dn.substring(0, space)) : firstName.trim();
        var last = lastName == null || lastName.isBlank()
                ? (space < 0 ? "" : dn.substring(space + 1).trim())
                : lastName.trim();
        return new UserProfile(
                id, first, last, email, phone, locale == null ? "en-CA" : locale, memberSince, mfaPrimary);
    }

    /** "RS" for Ravi Sandhu; "NL" when there is no name. */
    public String initials() {
        var s = (first(firstName) + first(lastName)).toUpperCase(Locale.ROOT);
        return s.isEmpty() ? "NL" : s;
    }

    private static String first(String s) {
        return s.isEmpty() ? "" : s.substring(0, 1);
    }
}
