package ca.northline.restricted.domain;

/** Messages of the age check (fr-CA in docs/spec/validation-messages.fr-CA.tsv). */
public final class AgeMessages {

    private AgeMessages() {}

    public static final String RETURN_URL = "Give the address to come back to after the ID check.";
    public static final String ALREADY = "Your age is already verified.";
    public static final String TOO_MANY =
            "You've tried the ID check too many times. Contact support and we'll help you finish it.";
    public static final String UNAVAILABLE = "The ID check isn't available right now. Try again in a moment.";

    /** Attempts a customer may start before support has to help. */
    public static final int MAX_ATTEMPTS = 5;
}
