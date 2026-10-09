package ca.northline.trust.domain;

import ca.northline.platform.Redaction;
import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The moderation hook a customer's review passes before it is stored: personal information goes through the log
 * redaction every module shares (S-112 {@link Redaction}: emails, phone numbers, card numbers, codes, postal codes) and a
 * short list of English and French profanities is masked. A review that needed either is still published, masked, and
 * a trust &amp; safety flag ({@code review_screened}) lets staff hide it. The list is deliberately small (slurs and
 * the common swear words): context decides the rest, in the console.
 */
public final class ReviewScreen {

    private ReviewScreen() {}

    /** @param masked something was masked */
    public record Screened(String text, boolean masked, List<String> reasons) {
        public Screened {
            reasons = List.copyOf(reasons);
        }
    }

    static final String MASK = "****";

    /**
     * Whole words (accents ignored), with the usual endings ("fucking", "shitty", "câlisses"); "fuck" also inside a
     * compound. Kept to words that are never innocent, so "estimate", "Scunthorpe" or "niggle" pass.
     */
    private static final Pattern PROFANE = Pattern.compile("(?:\\w*fuck\\w*|(?:shit|bitch|bastard|asshole|dickhead|cunt"
            + "|retard|faggot|nigger|nigga|tabarnak|tabarnac|tabernak|calisse|caliss|crisse|ciboire|putain|salope|connard"
            + "|connasse|encule|enculee)(?:s|es|ed|er|ers|ing|in|y|ty|e|ee)?)");

    private static final Pattern WORD = Pattern.compile("\\p{L}[\\p{L}'’-]*");

    public static Screened screen(String raw) {
        var reasons = new java.util.ArrayList<String>();
        var redacted = Redaction.redact(raw);
        if (!redacted.equals(raw)) {
            reasons.add("personal_info");
        }
        var m = WORD.matcher(redacted);
        var out = new StringBuilder();
        var profane = false;
        while (m.find()) {
            var word = fold(m.group());
            if (PROFANE.matcher(word).matches()) {
                profane = true;
                m.appendReplacement(out, Matcher.quoteReplacement(MASK));
            }
        }
        m.appendTail(out);
        if (profane) {
            reasons.add("profanity");
        }
        return new Screened(out.toString(), !reasons.isEmpty(), reasons);
    }

    private static String fold(String word) {
        return Normalizer.normalize(word, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
