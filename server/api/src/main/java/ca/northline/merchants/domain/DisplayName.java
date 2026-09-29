package ca.northline.merchants.domain;

import ca.northline.shared.RuleViolation;

/**
 * The name customers see (validation-rules.md › Business step › display_name: required, 2–80). Trimmed. The message
 * constants are the single source for both Bean Validation annotations on requests and this invariant.
 */
public record DisplayName(String value) {
    public static final String FIELD = "displayName";
    public static final int MIN = 2;
    public static final int MAX = 80;
    public static final String REQUIRED = "Enter the name customers will see.";
    public static final String TOO_SHORT = "At least 2 characters.";
    /** Not in the spec (DB CHECK is 2–80); see docs/DECISIONS.md. */
    public static final String TOO_LONG = "At most 80 characters.";

    public DisplayName {
        value = value.strip();
        if (value.isEmpty()) {
            throw RuleViolation.of(FIELD, "required", REQUIRED);
        }
        if (value.length() < MIN) {
            throw RuleViolation.of(FIELD, "length", TOO_SHORT);
        }
        if (value.length() > MAX) {
            throw RuleViolation.of(FIELD, "length", TOO_LONG);
        }
    }
}
