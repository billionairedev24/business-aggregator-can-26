package ca.northline.catalogue.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.Optional;
import java.util.regex.Pattern;

/** A GTIN-8/12/13/14 (UPC, EAN, ISBN-13) with a valid GS1 mod-10 check digit. Spaces and dashes are ignored. */
public record Gtin(String value) {

    private static final Pattern DIGITS = Pattern.compile("^(\\d{8}|\\d{12}|\\d{13}|\\d{14})$");

    public Gtin {
        value = normalize(value);
        var problem = problem("gtin", value);
        if (problem.isPresent()) {
            throw new RuleViolation(java.util.List.of(problem.get()));
        }
    }

    public static String normalize(String raw) {
        return raw.replaceAll("[\\s-]", "");
    }

    /** The first broken rule for {@code raw} reported on {@code field}, if any. */
    public static Optional<Violation> problem(String field, String raw) {
        var digits = normalize(raw);
        if (!DIGITS.matcher(digits).matches()) {
            return Optional.of(new Violation(field, "format", ListingMessages.GTIN_FORMAT));
        }
        if (!checkDigitOk(digits)) {
            return Optional.of(new Violation(field, "check_digit", ListingMessages.GTIN_CHECK_DIGIT));
        }
        return Optional.empty();
    }

    public static boolean isValid(String raw) {
        return problem("gtin", raw).isEmpty();
    }

    static boolean checkDigitOk(String digits) {
        int sum = 0;
        for (int i = digits.length() - 2, weight = 3; i >= 0; i--, weight = 4 - weight) {
            sum += (digits.charAt(i) - '0') * weight;
        }
        return (10 - sum % 10) % 10 == digits.charAt(digits.length() - 1) - '0';
    }
}
