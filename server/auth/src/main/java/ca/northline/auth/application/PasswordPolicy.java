package ca.northline.auth.application;

import ca.northline.auth.domain.AuthMessages;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-154, NIST SP 800-63B § 3.1.1: at least 12 characters (code points, after NFKC), at most 128, any characters
 * including spaces, no composition rules — and not one of the common or breached passwords bundled in
 * {@code auth/common-passwords.txt}, not the email address (or its part before the @), not one character repeated.
 * Passwords are compared and hashed in NFKC so the same password typed on two keyboards matches.
 */
@Component
public class PasswordPolicy {

    public static final int MIN = 12;
    public static final int MAX = 128;
    static final String LIST = "auth/common-passwords.txt";

    private final Set<String> common;

    public PasswordPolicy() {
        this(load());
    }

    PasswordPolicy(Set<String> common) {
        this.common = Set.copyOf(common);
    }

    /** The form the password is hashed and compared in. */
    public static String normalise(String password) {
        return Normalizer.normalize(password, Normalizer.Form.NFKC);
    }

    /** 422 on {@code field} with the first rule the password breaks. */
    public void require(String field, String password, @Nullable String email) {
        var violation = check(password, email);
        if (violation != null) {
            throw InvalidInput.of(field, violation.rule(), violation.message());
        }
    }

    /** The first broken rule, or null. */
    public InvalidInput.@Nullable Violation check(String password, @Nullable String email) {
        var p = normalise(password);
        var length = p.codePointCount(0, p.length());
        if (p.isBlank()) {
            return new InvalidInput.Violation("password", "required", AuthMessages.PASSWORD_REQUIRED);
        }
        if (length < MIN) {
            return new InvalidInput.Violation("password", "length", AuthMessages.PASSWORD_TOO_SHORT);
        }
        if (length > MAX) {
            return new InvalidInput.Violation("password", "length", AuthMessages.PASSWORD_TOO_LONG);
        }
        var lower = p.toLowerCase(Locale.ROOT);
        if (email != null && !email.isBlank()) {
            var address = email.strip().toLowerCase(Locale.ROOT);
            var at = address.indexOf('@');
            if (lower.equals(address) || (at > 0 && lower.equals(address.substring(0, at)))) {
                return new InvalidInput.Violation("password", "email", AuthMessages.PASSWORD_IS_EMAIL);
            }
        }
        if (common.contains(lower) || lower.codePoints().distinct().count() == 1) {
            return new InvalidInput.Violation("password", "common", AuthMessages.PASSWORD_COMMON);
        }
        return null;
    }

    private static Set<String> load() {
        var in = PasswordPolicy.class.getClassLoader().getResourceAsStream(LIST);
        try (var reader =
                new BufferedReader(new InputStreamReader(Objects.requireNonNull(in, LIST), StandardCharsets.UTF_8))) {
            return reader.lines()
                    .map(String::strip)
                    .filter(l -> !l.isEmpty() && !l.startsWith("#"))
                    .map(l -> l.toLowerCase(Locale.ROOT))
                    .collect(Collectors.toUnmodifiableSet());
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
