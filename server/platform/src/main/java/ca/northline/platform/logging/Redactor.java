package ca.northline.platform.logging;

import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Function;
import java.util.regex.MatchResult;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;

/**
 * Masks personal data and secrets in log text (S-112). Applied to every string of a structured log line
 * ({@link RedactingJsonMembersCustomizer}) and of every log record exported over OTLP ({@link OtlpLogAppender}), so a
 * careless {@code log.info("… {}", email)}, a library's exception message or a provider's error body cannot leak.
 *
 * <p>Two layers. A field whose <em>name</em> is sensitive ({@code password}, {@code token}, {@code authorization},
 * {@code code}, …) is replaced whole. Every other string is scanned, in this order:
 *
 * <ol>
 *   <li>secrets: PEM private keys, {@code Authorization}/{@code Cookie} echoes, bearer/basic credentials, JWTs, Stripe
 *       ({@code sk_}, {@code rk_}, {@code whsec_}), OpenRouter/OpenAI ({@code sk-…}), AWS access keys, GitHub tokens,
 *       {@code key=value} / {@code "key":"value"} pairs with a sensitive key;
 *   <li>email addresses → {@code [EMAIL]};
 *   <li>card-like numbers (13–19 digits, spaces or dashes allowed, Luhn-valid) → {@code [CARD …4242]};
 *   <li>phone numbers (North American, any punctuation; E.164) → {@code [PHONE]}. The masked form
 *       {@code +1 403 *** **48} the SMS adapters log is left as it is;
 *   <li>one-time codes following "verification / sign-in / one-time / security / backup / OTP code" (en/fr) →
 *       {@code [CODE]};
 *   <li>Canadian postal codes → the forward sortation area only ({@code T2P ***}).
 * </ol>
 *
 * Ids stay readable: a number glued to letters or underscores ({@code po_1234567890}, ULIDs) is never touched.
 */
public final class Redactor {

    public static final String MASK = "[REDACTED]";

    private static final Pattern SENSITIVE_NAME = Pattern.compile(
            "(?i)(^|.*[._-])(authorization|proxy-authorization|cookie|set-cookie|password|passwd|pwd|secret"
                    + "|client[-_.]?secret|token|access[-_.]?token|refresh[-_.]?token|id[-_.]?token|api[-_.]?key|apikey"
                    + "|credentials?|private[-_.]?key|totp|otp|code|backup[-_.]?codes?|verification[-_.]?code"
                    + "|x-xsrf-token|x-dev-user|card[-_.]?number|pan|sin)($|[._-].*)");

    private static final String NOT_WORD_BEFORE = "(?<![\\w*+@.-])";
    private static final String NOT_WORD_AFTER = "(?![\\w@-])";

    private static final List<Rule> RULES = List.of(
            // Secrets first: their bodies may look like emails, numbers or phone numbers.
            Rule.fixed("-----BEGIN [A-Z ]*PRIVATE KEY-----[\\s\\S]*?(-----END [A-Z ]*PRIVATE KEY-----|$)", MASK),
            Rule.fixed(
                    "(?i)\\b((?:proxy-)?authorization)(\"?\\s*[:=]\\s*\"?)((?:bearer|basic|dpop|token)\\s+)?[^\\s\",;}]+",
                    "$1$2" + MASK),
            Rule.fixed("(?i)\\b(set-cookie|cookie)(\"?\\s*[:=]\\s*\"?)([^\"\\r\\n}]+)", "$1$2" + MASK),
            Rule.fixed("(?i)\\b(bearer|basic|dpop)\\s+[A-Za-z0-9._~+/=-]{8,}", "$1 " + MASK),
            Rule.fixed("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]*", MASK),
            Rule.fixed("\\b(sk|rk)_(live|test)_[A-Za-z0-9]{6,}", "$1_$2_" + MASK),
            Rule.fixed("\\bwhsec_[A-Za-z0-9+/=]{6,}", "whsec_" + MASK),
            Rule.fixed("\\bsk-[A-Za-z0-9_-]{10,}", "sk-" + MASK),
            Rule.fixed("\\b(AKIA|ASIA)[A-Z0-9]{16}\\b", MASK),
            Rule.fixed("\\bgh[pousr]_[A-Za-z0-9]{20,}", MASK),
            Rule.fixed("(?i)otpauth://[^\\s\"']+", "otpauth://" + MASK),
            Rule.of(
                    "(?i)(\"?\\b[\\w.-]*(?:passw(?:or)?d|pwd|secret|token|api[-_]?key|apikey|credentials?|totp|otp"
                            + "|(?:verification|backup|sign-?in)[-_ ]?codes?)\\b\"?\\s*[:=]\\s*)"
                            + "(\"[^\"]*\"|'[^']*'|[^\\s,;&}\"']+)",
                    m -> {
                        var value = m.group(2);
                        var quote = value.startsWith("\"") ? "\"" : value.startsWith("'") ? "'" : "";
                        return m.group(1) + quote + MASK + quote;
                    }),
            // Personal data.
            Rule.fixed("[A-Za-z0-9._%+-]+@[A-Za-z0-9-]+(\\.[A-Za-z0-9-]+)*\\.[A-Za-z]{2,}", "[EMAIL]"),
            Rule.of(NOT_WORD_BEFORE + "\\d(?:[ -]?\\d){12,18}" + NOT_WORD_AFTER, Redactor::card),
            Rule.fixed(
                    NOT_WORD_BEFORE + "(?:\\+?1[\\s.-]?)?\\(?[2-9]\\d{2}\\)?[\\s.-]?[2-9]\\d{2}[\\s.-]?\\d{4}"
                            + NOT_WORD_AFTER,
                    "[PHONE]"),
            Rule.fixed(NOT_WORD_BEFORE + "\\+[1-9]\\d{7,14}" + NOT_WORD_AFTER, "[PHONE]"),
            Rule.fixed(
                    "(?i)(\\b(?:verification|v[ée]rification|sign-in|one-time|security|s[ée]curit[ée]|backup|otp|mfa"
                            + "|totp|authenticator)\\b[^\\r\\n]{0,80}?)" + NOT_WORD_BEFORE + "\\d{4,8}"
                            + NOT_WORD_AFTER,
                    "$1[CODE]"),
            Rule.fixed(
                    "(?i)" + NOT_WORD_BEFORE + "([ABCEGHJ-NPRSTVXY]\\d[ABCEGHJ-NPRSTV-Z])[ -]?\\d[ABCEGHJ-NPRSTV-Z]\\d"
                            + NOT_WORD_AFTER,
                    "$1 ***"));

    private Redactor() {}

    /** True when a field called {@code name} holds a secret or personal data and is masked whole. */
    public static boolean isSensitiveName(@Nullable String name) {
        if (name == null || name.isEmpty()) {
            return false;
        }
        var n = name.toLowerCase(Locale.ROOT);
        // Correlation ids and event metadata are not secrets even when they end in "id" or "code".
        if (n.equals("trace.id")
                || n.equals("span.id")
                || n.equals("traceid")
                || n.equals("spanid")
                || n.endsWith("event.id")
                || n.equals("error.code")
                || n.equals("status.code")
                || n.endsWith("status_code")) {
            return false;
        }
        return SENSITIVE_NAME.matcher(n).matches();
    }

    /** {@code value} with every secret- or PII-shaped part masked; {@code null} stays {@code null}. */
    public static @Nullable String redact(@Nullable String value) {
        if (value == null || value.isEmpty()) {
            return value;
        }
        var out = value;
        for (var rule : RULES) {
            out = rule.apply(out);
        }
        return out;
    }

    /** The value of a field called {@code name}: masked whole when the name is sensitive, else {@link #redact}. */
    public static @Nullable String redact(@Nullable String name, @Nullable String value) {
        if (value == null) {
            return null;
        }
        return isSensitiveName(name) ? MASK : redact(value);
    }

    private static String card(MatchResult m) {
        var digits = m.group().replaceAll("[ -]", "");
        return luhn(digits) ? "[CARD …" + digits.substring(digits.length() - 4) + "]" : m.group();
    }

    static boolean luhn(String digits) {
        var sum = 0;
        var doubled = false;
        for (var i = digits.length() - 1; i >= 0; i--) {
            var d = digits.charAt(i) - '0';
            if (doubled) {
                d *= 2;
                if (d > 9) {
                    d -= 9;
                }
            }
            sum += d;
            doubled = !doubled;
        }
        return sum % 10 == 0;
    }

    /** A pattern and what replaces each match: a {@code $n} template, or a function of the match. */
    private record Rule(
            Pattern pattern,
            @Nullable String template,
            @Nullable Function<MatchResult, String> function) {

        static Rule fixed(String regex, String template) {
            return new Rule(Pattern.compile(regex), template, null);
        }

        static Rule of(String regex, Function<MatchResult, String> function) {
            return new Rule(Pattern.compile(regex), null, function);
        }

        String apply(String input) {
            var matcher = pattern.matcher(input);
            if (!matcher.find()) {
                return input;
            }
            matcher.reset();
            if (template != null) {
                return matcher.replaceAll(template);
            }
            var replace = Objects.requireNonNull(function);
            return matcher.replaceAll(m -> Matcher.quoteReplacement(replace.apply(m)));
        }
    }
}
