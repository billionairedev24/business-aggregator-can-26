package ca.northline.auth.application;

import java.time.Instant;
import java.util.Locale;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Read model of {@code identity.users} as the auth server needs it (claims, sign-in, session). */
public record UserAccount(
        String id,
        @Nullable String firstName,
        @Nullable String lastName,
        @Nullable String displayName,
        @Nullable String email,
        @Nullable String phone,
        @Nullable String locale,
        @Nullable String mfaPrimary,
        String status,
        Instant createdAt) {

    /** First name; falls back to the first word of display_name for rows created before V020. */
    public String givenName() {
        if (firstName != null && !firstName.isBlank()) {
            return firstName;
        }
        var dn = Objects.requireNonNullElse(displayName, "").trim();
        int space = dn.indexOf(' ');
        return space < 0 ? dn : dn.substring(0, space);
    }

    public String familyName() {
        if (lastName != null && !lastName.isBlank()) {
            return lastName;
        }
        var dn = Objects.requireNonNullElse(displayName, "").trim();
        int space = dn.indexOf(' ');
        return space < 0 ? "" : dn.substring(space + 1);
    }

    public boolean active() {
        return "active".equals(status.toLowerCase(Locale.ROOT));
    }
}
