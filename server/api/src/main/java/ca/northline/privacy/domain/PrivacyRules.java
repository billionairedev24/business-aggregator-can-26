package ca.northline.privacy.domain;

import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/** Rules and messages of privacy requests (en; fr-CA in docs/spec/validation-messages.fr-CA.tsv). */
public final class PrivacyRules {

    public static final String TYPE_REQUIRED = "Choose what you're asking for: a copy, a correction or deletion.";
    public static final String CORRECTIONS_REQUIRED = "Say what to correct.";
    public static final String CORRECTION_FIELD = "Choose a detail we can correct.";
    public static final String CORRECTION_VALUE = "Enter the correct value, up to 200 characters.";
    public static final String NOTE_LENGTH = "Keep the note under 1,000 characters.";
    public static final String CODE_FORMAT = "Enter the 6-digit code we texted you.";
    public static final String CODE_WRONG = "That code didn't match. Check the text and try again.";
    public static final String CONTACT_REQUIRED = "Enter the person's email or mobile number.";
    public static final String NO_ACCOUNT = "No Northline account uses that email or mobile number.";
    public static final String DECISION_REQUIRED = "Choose why the request is refused.";
    public static final String EXTENSION_REQUIRED = "Choose why more time is needed.";
    public static final String DECISION_NOTE = "At most 500 characters.";

    public static final String REQUEST_OPEN = "You already asked for this. We're working on it.";
    public static final String NOT_AWAITING = "This request is already verified or closed.";
    public static final String CODE_LOCKED = "Too many wrong codes. Ask for a new code.";
    public static final String CODE_NOT_SENT = "We couldn't text the code. Try again in a minute.";
    public static final String TOO_MANY_CODES = "We've texted you several codes already. Try again later, or confirm"
            + " it's you with your passkey or authenticator app.";
    public static final String CODE_TOO_SOON = "We just sent a code. Wait a minute before asking for another.";
    public static final String NO_MOBILE =
            "Your account has no verified mobile number. Confirm it's you with your passkey"
                    + " or authenticator app instead.";
    public static final String NOT_WITHDRAWABLE = "Deletion has started and can't be cancelled.";
    public static final String CLOSED = "This request is closed.";
    public static final String NO_EXTENSION = "The law that applies to this request allows no extension.";
    public static final String ALREADY_EXTENDED = "This request was already extended once.";
    public static final String NOT_STARTABLE = "Only a verified deletion request that hasn't started can be started.";
    public static final String NOT_CORRECTION = "Only a verified correction request can be applied.";
    public static final String EXPORT_GONE = "This download has expired. Ask for a new copy of your data.";
    public static final String EXPORT_NOT_READY = "Your data isn't ready yet.";
    public static final String ACCOUNT_ERASED = "This account has been deleted.";

    public static final int VALUE_MAX = 200;
    public static final int NOTE_MAX = 1000;
    public static final int MAX_CODE_ATTEMPTS = 5;

    private PrivacyRules() {}

    /** A requested correction: a field code a module corrects, and the value the person says is right. */
    public record Correction(String field, String value) {}

    /** Checks a correction request against the fields modules can correct; every problem at once. */
    public static List<Correction> corrections(@Nullable List<Correction> asked, Set<String> correctable) {
        if (asked == null || asked.isEmpty()) {
            throw RuleViolation.of("corrections", "required", CORRECTIONS_REQUIRED);
        }
        for (var i = 0; i < asked.size(); i++) {
            var c = asked.get(i);
            if (!correctable.contains(c.field())) {
                throw RuleViolation.of("corrections[%d].field".formatted(i), "allowed", CORRECTION_FIELD);
            }
            var value = c.value().strip();
            if (value.isEmpty() || value.length() > VALUE_MAX) {
                throw RuleViolation.of("corrections[%d].value".formatted(i), "length", CORRECTION_VALUE);
            }
        }
        return asked.stream()
                .map(c -> new Correction(c.field(), c.value().strip()))
                .toList();
    }

    /** "PR-1001". */
    public static String reference(long number) {
        return "PR-" + number;
    }

    /** "•••• 0148": the last four digits of a mobile number. */
    public static String maskedPhone(String phone) {
        return "•••• " + phone.substring(Math.max(0, phone.length() - 4));
    }
}
