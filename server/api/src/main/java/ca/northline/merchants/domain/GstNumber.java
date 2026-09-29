package ca.northline.merchants.domain;

import ca.northline.shared.RuleViolation;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * GST/HST registration number (validation-rules.md › Business step › gst_number). Accepts {@code ^\d{9}\s?RT\s?\d{4}$}
 * (case-insensitive, as the design's client check) and stores the canonical {@code 123456789 RT0001}, which also
 * satisfies the V016 {@code chk_gst_format} constraint.
 */
public record GstNumber(String value) {
    public static final String FIELD = "gstNumber";
    public static final String REGEX = "^\\d{9}\\s?[Rr][Tt]\\s?\\d{4}$";
    public static final String FORMAT = "Format is 9 digits + RT0001 (e.g. 123456789 RT0001).";
    public static final String REQUIRED = "Required for corporations, co-ops and non-profits.";
    private static final Pattern PATTERN = Pattern.compile(REGEX);

    public GstNumber {
        var raw = value.strip();
        if (!PATTERN.matcher(raw).matches()) {
            throw RuleViolation.of(FIELD, "format", FORMAT);
        }
        var compact = raw.replaceAll("\\s", "").toUpperCase(Locale.ROOT);
        value = compact.substring(0, 9) + " " + compact.substring(9);
    }

    public static boolean isValid(String raw) {
        return PATTERN.matcher(raw.strip()).matches();
    }
}
