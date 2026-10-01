package ca.northline.messaging.domain;

import java.util.regex.Pattern;

/**
 * A message body as stored and shown: phone numbers and email addresses are masked ("Phone numbers are masked;
 * messages are kept for disputes.") and the off-platform detector flags attempts to take contact or payment off
 * Northline (no off-platform payments — CLAUDE.md).
 *
 * @param text the masked text
 * @param offPlatform true when contact details were masked or the text asks for payment outside Northline
 */
public record OutgoingText(String text, boolean offPlatform) {

    public static final String MASKED_PHONE = "•••-•••-••••";
    public static final String MASKED_EMAIL = "•••@•••";

    private static final Pattern PHONE =
            Pattern.compile("(?<![\\d•])(?:\\+?1[\\s.-]?)?\\(?\\d{3}\\)?[\\s.-]?\\d{3}[\\s.-]?\\d{4}(?!\\d)");
    private static final Pattern EMAIL = Pattern.compile("[\\p{L}0-9._%+-]+@[\\p{L}0-9.-]+\\.\\p{L}{2,}");
    private static final Pattern OFF_PLATFORM_PAYMENT = Pattern.compile(
            "(?iu)\\b(e-?transfer|etransfer|interac|venmo|paypal|zelle|bitcoin|crypto|cash only|cash instead"
                    + "|pay (?:me )?(?:in )?cash|pay (?:me )?directly|outside (?:the )?(?:app|northline)"
                    + "|virement|en argent comptant|payer directement|hors de l'appli)\\b");

    public static OutgoingText of(String raw) {
        return of(raw, java.util.List.of());
    }

    /**
     * S-93: with the phrases trust &amp; safety configured on top of the built-in patterns (lower-case, matched
     * case-insensitively anywhere in the text).
     */
    public static OutgoingText of(String raw, java.util.List<String> phrases) {
        var text = raw.strip();
        var phone = PHONE.matcher(text);
        boolean masked = phone.find();
        text = phone.replaceAll(MASKED_PHONE);
        var email = EMAIL.matcher(text);
        masked |= email.find();
        text = email.replaceAll(MASKED_EMAIL);
        var lower = text.toLowerCase(java.util.Locale.ROOT);
        return new OutgoingText(
                text,
                masked
                        || OFF_PLATFORM_PAYMENT.matcher(text).find()
                        || phrases.stream().anyMatch(lower::contains));
    }
}
