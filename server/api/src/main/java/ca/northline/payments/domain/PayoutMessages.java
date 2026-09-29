package ca.northline.payments.domain;

/**
 * Validation messages of the Payouts screen. validation-rules.md has none for payouts; these are ours (DECISIONS.md,
 * Finance workstream) and the Studio shows the same text (en + fr) for the same field + rule.
 */
public final class PayoutMessages {
    public static final String AMOUNT_REQUIRED = "Enter an amount.";
    public static final String AMOUNT_MIN = "Instant payouts start at $1.00.";
    /** Formatted with the available amount, e.g. "You can pay out up to $822.60." */
    public static final String AMOUNT_MAX = "You can pay out up to %s.";

    public static final String SCHEDULE_REQUIRED = "Choose a payout schedule.";
    public static final String RESERVE_REQUIRED = "Choose a reserve option.";

    public static final String METHOD_REQUIRED = "Choose how to add the account.";
    public static final String LINKED_ACCOUNT_REQUIRED = "Connect your bank first.";
    public static final String INSTITUTION_FORMAT = "Enter the 3-digit institution number.";
    public static final String TRANSIT_FORMAT = "Enter the 5-digit transit number.";
    public static final String ACCOUNT_FORMAT = "Account numbers are 7 to 12 digits.";
    public static final String HOLDER_REQUIRED = "Enter the account holder's legal name.";
    public static final String HOLDER_TOO_LONG = "At most 120 characters.";

    public static final String INSTITUTION_PATTERN = "^\\d{3}$";
    public static final String TRANSIT_PATTERN = "^\\d{5}$";
    public static final String ACCOUNT_PATTERN = "^\\d{7,12}$";

    private PayoutMessages() {}
}
