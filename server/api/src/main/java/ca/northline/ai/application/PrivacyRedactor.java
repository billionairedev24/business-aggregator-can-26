package ca.northline.ai.application;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Masks what must never reach a model provider (OpenRouter is a US processor; DECISIONS.md S-129): card numbers and SINs
 * (Luhn-checked), Canadian bank account numbers (transit-institution-account and "account 1234567"), IBANs, email
 * addresses, phone numbers and API keys. Applied to every message on the port, so a tool or a draft that forgets
 * still can't leak them. The placeholder keeps the signal ("[phone]" in a message is itself an off-platform hint).
 */
public final class PrivacyRedactor {
    private PrivacyRedactor() {}

    private record Rule(Pattern pattern, String mask, boolean luhn) {}

    private static final List<Rule> RULES = List.of(
            new Rule(
                    Pattern.compile("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)"),
                    "[secret]",
                    false),
            new Rule(Pattern.compile("\\b(?:sk|rk|pk)[-_](?:or-|live_|test_)?[A-Za-z0-9_-]{12,}"), "[secret]", false),
            new Rule(Pattern.compile("[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}"), "[email]", false),
            new Rule(
                    Pattern.compile("\\b[A-Z]{2}\\d{2}(?: ?[A-Z0-9]{4}){3,7}(?: ?[A-Z0-9]{1,3})?\\b"),
                    "[bank account]",
                    false),
            // Card numbers: 13–19 digits, optionally grouped by spaces or dashes, Luhn-valid.
            new Rule(Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b"), "[card number]", true),
            // Cheque format: transit (5) · institution (3) · account (7–12).
            new Rule(Pattern.compile("\\b\\d{5}[- ]\\d{3}[- ]\\d{7,12}\\b"), "[bank account]", false),
            new Rule(
                    Pattern.compile("(?i)\\b((?:bank |chequing |checking |savings )?(?:account|acct|compte)"
                            + "(?: (?:number|no\\.?|#|numéro))?\\s*[:#]?\\s*)\\d[\\d -]{5,16}\\d\\b"),
                    "$1[bank account]",
                    false),
            // SIN: 9 digits (3-3-3), Luhn-valid.
            new Rule(Pattern.compile("\\b\\d{3}[ -]?\\d{3}[ -]?\\d{3}\\b"), "[SIN]", true),
            new Rule(
                    Pattern.compile("(?<![\\w-])(?:\\+?1[ .-]?)?\\(?[2-9]\\d{2}\\)?[ .-]?\\d{3}[ .-]?\\d{4}(?![\\w-])"),
                    "[phone]",
                    false));

    /** {@code text} with every sensitive value masked. */
    public static String redact(String text) {
        if (text.isEmpty()) {
            return text;
        }
        var out = text;
        for (var rule : RULES) {
            Matcher m = rule.pattern().matcher(out);
            if (!m.find()) {
                continue;
            }
            m.reset();
            var sb = new StringBuilder();
            while (m.find()) {
                var hit = m.group();
                var replace = !rule.luhn() || luhn(hit.replaceAll("\\D", ""));
                m.appendReplacement(sb, replace ? rule.mask() : Matcher.quoteReplacement(hit));
            }
            m.appendTail(sb);
            out = sb.toString();
        }
        return out;
    }

    static boolean luhn(String digits) {
        int sum = 0;
        boolean dbl = false;
        for (int i = digits.length() - 1; i >= 0; i--) {
            int d = digits.charAt(i) - '0';
            if (dbl) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            dbl = !dbl;
        }
        return !digits.isEmpty() && sum % 10 == 0;
    }
}
