package ca.northline.platform.pci;

import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * What cardholder data looks like (S-110, PCI DSS SAQ A): Northline never receives, logs or stores it — card entry is
 * Stripe's (Payment Element, PaymentSheet), and Northline only sees PaymentIntent / PaymentMethod ids, brand, last four
 * and expiry. This class is how the code and the tests recognise it when it shows up anyway:
 *
 * <ul>
 *   <li>a <b>PAN</b>: 13–19 digits, single spaces or dashes allowed between them, Luhn-valid, starting with a card
 *       brand's issuer range ({@link #findPans}). A number glued to a letter, {@code _}, {@code +} or {@code @} is not
 *       one (ULIDs, Stripe ids, E.164 phone numbers);
 *   <li><b>track data</b>: magnetic-stripe track 1 ({@code %B<PAN>^NAME^YYMM…}) or track 2 ({@code ;<PAN>=YYMM…});
 *   <li>a <b>card verification code</b> next to its name ({@code cvc: 123}, {@code "cvv2":"1234"}, "security code
 *       123");
 *   <li>a <b>field name</b> that can only hold card data ({@code cardNumber}, {@code pan}, {@code cvc}, {@code track2},
 *       …) — {@link #isCardDataName}.
 * </ul>
 *
 * Used by the api's request guard ({@code shared.web.CardDataGuard}) and by the S-110 scanners over the schema, the
 * seeds, the event and OpenAPI contracts.
 */
public final class CardData {

    /** A PAN as it may be typed: 13–19 digits with optional single separators, not glued to an id or phone number. */
    private static final Pattern CANDIDATE = Pattern.compile("(?<![\\w*+@.-])\\d(?:[ -]?\\d){12,18}(?![\\w@-])");

    private static final Pattern TRACK_1 = Pattern.compile("%?B\\d{13,19}\\^[^^\\n]{2,26}\\^\\d{4}");
    private static final Pattern TRACK_2 = Pattern.compile(";?\\d{13,19}=\\d{4}\\d*\\??");

    private static final Pattern CVC = Pattern.compile(
            "(?i)\\b(?:cvv2?|cvc2?|cvn|csc|card[ _-]?verification(?:[ _-]?(?:code|value))?|security[ _-]?code)\\b"
                    + "[\"']?\\s*[:=]?\\s*[\"']?\\d{3,4}\\b");

    /**
     * Field and column names that can only mean card data. Tokens of a camelCase / snake_case / kebab name:
     * {@code card_number}, {@code cardNumber}, {@code primaryAccountNumber}, {@code pan}, {@code cvc}, {@code cvv},
     * {@code track1}, {@code magstripe}, {@code pin_block}. Not {@code last4}, {@code brand}, {@code exp_month}.
     */
    private static final Pattern NAME = Pattern.compile(
            "(?:^|_)(?:card_?num(?:ber)?|card_?no|ccnum|cc_?number|credit_?card(?:_?number)?|primary_?account_?number"
                    + "|pan|full_?pan|cvc2?|cvv2?|cvn|csc|card_?verification(?:_?(?:code|value))?|card_?security_?code"
                    + "|track_?[12]|track_?data|magstripe|mag_?stripe|pin_?block)(?:$|_)");

    private static final Pattern CAMEL = Pattern.compile("(?<=[a-z0-9])(?=[A-Z])");

    /** A PAN found in a text: where it starts and its digits. {@link #masked()} is what may be shown or logged. */
    public record Pan(int start, int end, String digits) {

        public String masked() {
            return mask(digits);
        }
    }

    private CardData() {}

    /** Every PAN in {@code text}, in order. */
    public static List<Pan> findPans(String text) {
        return CANDIDATE
                .matcher(text)
                .results()
                .map(m -> new Pan(m.start(), m.end(), m.group().replaceAll("[ -]", "")))
                .filter(p -> isPan(p.digits()))
                .toList();
    }

    public static boolean containsPan(String text) {
        return !findPans(text).isEmpty();
    }

    /** Digits only: 13–19 long, a card brand's issuer range and Luhn-valid. */
    public static boolean isPan(String digits) {
        return digits.length() >= 13
                && digits.length() <= 19
                && digits.chars().allMatch(Character::isDigit)
                && issuer(digits)
                && luhn(digits);
    }

    public static boolean containsTrackData(String text) {
        return TRACK_1.matcher(text).find()
                || TRACK_2.matcher(text).results().anyMatch(m -> {
                    var digits = m.group().replaceFirst("^;", "").split("=", 2)[0];
                    return isPan(digits);
                });
    }

    /** A card verification code next to its name ({@code cvc 123}, {@code "cvv":"1234"}). */
    public static boolean containsVerificationCode(String text) {
        return CVC.matcher(text).find();
    }

    /** PAN, track data or a verification code: anything SAQ A says Northline must never hold. */
    public static boolean containsCardData(String text) {
        return containsPan(text) || containsTrackData(text) || containsVerificationCode(text);
    }

    /** True for a field or column name that can only hold card data ({@code cardNumber}, {@code cvc}, {@code pan}). */
    public static boolean isCardDataName(String name) {
        var snake = CAMEL.matcher(name)
                .replaceAll("_")
                .replace('-', '_')
                .replace('.', '_')
                .toLowerCase(Locale.ROOT);
        return NAME.matcher(snake).find();
    }

    /** {@code [CARD …4242]}: the brand-neutral form the logs and the Redactor use. */
    public static String mask(String digits) {
        var only = digits.replaceAll("\\D", "");
        return "[CARD …" + only.substring(Math.max(0, only.length() - 4)) + "]";
    }

    public static boolean luhn(String digits) {
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

    /**
     * Issuer ranges of the brands a Canadian card can carry: Visa 4, Mastercard 51–55 and 2221–2720, American Express
     * 34/37, Discover 6011/644–649/65, JCB 3528–3589, Diners 300–305/36/38–39, UnionPay 62/81, Maestro 50/56–58/63/67,
     * Interac-co-badged cards ride on these.
     */
    private static boolean issuer(String digits) {
        var two = Integer.parseInt(digits.substring(0, 2));
        var three = Integer.parseInt(digits.substring(0, 3));
        var four = Integer.parseInt(digits.substring(0, 4));
        return digits.charAt(0) == '4'
                || (two >= 51 && two <= 55)
                || (four >= 2221 && four <= 2720)
                || two == 34
                || two == 37
                || four == 6011
                || (three >= 644 && three <= 649)
                || two == 65
                || (four >= 3528 && four <= 3589)
                || (three >= 300 && three <= 305)
                || two == 36
                || two == 38
                || two == 39
                || two == 62
                || two == 81
                || two == 50
                || (two >= 56 && two <= 58)
                || two == 63
                || two == 67;
    }
}
