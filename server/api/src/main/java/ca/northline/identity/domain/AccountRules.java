package ca.northline.identity.domain;

import ca.northline.shared.RuleViolation;
import java.time.MonthDay;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The consumer account's own rules (S-59, design 06 profile and addresses). Names and email use the registration
 * messages of docs/spec/validation-rules.md; address messages are checkout's (S-51), so a person reads the same
 * sentence wherever they type an address.
 */
public final class AccountRules {
    private AccountRules() {}

    public static final int NAME_MAX = 60;
    public static final String FIRST_NAME_REQUIRED = "First name is required.";
    public static final String LAST_NAME_REQUIRED = "Last name is required.";
    public static final String NAME_TOO_LONG = "Keep names under 60 characters.";
    public static final String EMAIL_REQUIRED = "Email is required.";
    public static final String EMAIL_FORMAT = "That doesn't look like an email address.";
    public static final String EMAIL_PATTERN = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";
    public static final String EMAIL_TAKEN = "That email is already used by another account.";
    public static final String PRONOUNS = "Choose from the list.";
    public static final String BIRTHDAY = "Enter a birthday like 03/14 (month / day).";

    /** {@code she | he | they | none} ("Prefer not to say"). */
    public static final Set<String> PRONOUN_CODES = Set.of("she", "he", "they", "none");

    public static final String STREET = "Enter the street address.";
    public static final String CITY = "Enter the city.";
    public static final String PROVINCE = "Choose a Canadian province or territory.";
    public static final String POSTAL = "Enter a Canadian postal code, like T2P 1B5.";
    public static final String POSTAL_PATTERN = "^[A-Za-z]\\d[A-Za-z][ -]?\\d[A-Za-z]\\d$";
    public static final String UNIT = "Keep the unit under 20 characters.";
    public static final String NOTE = "Keep delivery notes under 200 characters.";
    public static final String LABEL = "Keep the name under 40 characters.";
    public static final String LAST_ADDRESS_DEFAULT = "This is already your default address.";

    /** Canada's provinces and territories. */
    public static final List<String> PROVINCES =
            List.of("AB", "BC", "MB", "NB", "NL", "NS", "NT", "NU", "ON", "PE", "QC", "SK", "YT");

    /** "MM-DD" → a month-day; blank → none; anything else is a 422 on {@code birthday}. */
    public static @Nullable MonthDay birthday(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        var m = java.util.regex.Pattern.compile("^\\s*(\\d{1,2})\\s*[-/]\\s*(\\d{1,2})\\s*$")
                .matcher(value);
        if (!m.matches()) {
            throw RuleViolation.of("birthday", "format", BIRTHDAY);
        }
        try {
            return MonthDay.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)));
        } catch (java.time.DateTimeException e) {
            throw RuleViolation.of("birthday", "format", BIRTHDAY);
        }
    }

    /** "t2p1b5" → "T2P 1B5". */
    public static String postal(String value) {
        var compact = value.replaceAll("[\\s-]", "").toUpperCase(java.util.Locale.ROOT);
        return compact.length() == 6 ? compact.substring(0, 3) + " " + compact.substring(3) : value.strip();
    }

    public static String province(String value) {
        var code = value.strip().toUpperCase(java.util.Locale.ROOT);
        if (!PROVINCES.contains(code)) {
            throw RuleViolation.of("province", "allowed", PROVINCE);
        }
        return code;
    }
}
