package ca.northline.promotions.domain;

import java.util.Locale;

/** Exact messages of promo codes (en; fr-CA in docs/spec/validation-messages.fr-CA.tsv). */
public final class PromoMessages {
    private PromoMessages() {}

    // checkout (field promoCode)
    public static final String UNKNOWN = "This code isn't valid.";
    public static final String NOT_YET = "This code isn't active yet.";
    public static final String EXPIRED = "This code has expired.";
    public static final String USED = "You've already used this code.";
    public static final String USED_UP = "This code has been fully used.";
    public static final String NOT_HERE = "This code doesn't apply to this order.";
    public static final String MIN_SPEND = "Spend at least $%d.%02d to use this code.";

    // console
    public static final String CODE_FORMAT = "Use 3 to 20 letters, digits or dashes.";
    public static final String CODE_TAKEN = "That code already exists.";
    public static final String KIND = "Choose percent or amount off.";
    public static final String PERCENT = "Enter a percentage from 1 to 100.";
    public static final String AMOUNT = "Enter an amount of at least $1.00.";
    public static final String MAX_DISCOUNT = "Enter a cap of at least $1.00, or none.";
    public static final String MIN_SPEND_RANGE = "Enter a minimum spend of $0 or more.";
    public static final String WINDOW = "End the code after it starts.";
    public static final String WINDOW_REQUIRED = "Choose when the code starts and ends.";
    public static final String LIMIT = "Enter a limit of 1 or more.";
    public static final String FUNDER = "Choose who funds the code.";
    public static final String MERCHANT = "Choose the business that funds this code.";
    public static final String APPLIES_TO = "Choose at least one of shop, food or services.";
    public static final String DESCRIPTION = "At most 200 characters.";

    public static String minSpend(long cents) {
        return MIN_SPEND.formatted(cents / 100, cents % 100);
    }

    public static String normalise(String raw) {
        return raw.replaceAll("\\s+", "").toUpperCase(Locale.ROOT);
    }
}
