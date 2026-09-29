package ca.northline.auth.domain;

import java.io.Serializable;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A Canadian (NANP) mobile number, stored as E.164 ({@code +14035550148}). Accepts the spec's input pattern
 * ({@code validation-rules.md}: {@code ^\+?1?[\s.-]?\(?\d{3}\)?[\s.-]?\d{3}[\s.-]?\d{4}$}).
 */
public record PhoneNumber(String e164) implements Serializable {

    public static final String PATTERN = "^\\+?1?[\\s.-]?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}$";
    private static final Pattern INPUT = Pattern.compile(PATTERN);

    public PhoneNumber {
        if (!e164.matches("^\\+1\\d{10}$")) {
            throw new IllegalArgumentException("Not an E.164 NANP number");
        }
    }

    /** Parses user input; empty when it doesn't match the spec pattern. */
    public static Optional<PhoneNumber> parse(String raw) {
        var trimmed = raw.trim();
        if (!INPUT.matcher(trimmed).matches()) {
            return Optional.empty();
        }
        var digits = trimmed.replaceAll("\\D", "");
        var national = digits.length() == 11 ? digits.substring(1) : digits;
        return national.length() == 10 ? Optional.of(new PhoneNumber("+1" + national)) : Optional.empty();
    }

    /** "+1 403 *** **48" — for logs (PII: never the whole number). */
    public String masked() {
        return "+1 %s *** **%s".formatted(e164.substring(2, 5), e164.substring(10));
    }

    /** "+1 403 555 0148" — how the Studio shows a number. */
    public String display() {
        return "+1 %s %s %s".formatted(e164.substring(2, 5), e164.substring(5, 8), e164.substring(8));
    }
}
