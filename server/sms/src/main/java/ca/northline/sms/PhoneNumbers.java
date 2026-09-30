package ca.northline.sms;

import java.util.regex.Pattern;

/** E.164 helpers: validation and the masked form logs use ({@code +1 403 *** **48}). */
public final class PhoneNumbers {

    public static final Pattern E164 = Pattern.compile("\\+[1-9]\\d{6,14}");

    private PhoneNumbers() {}

    public static boolean isE164(String number) {
        return E164.matcher(number).matches();
    }

    /** {@code +1 403 *** **48} for North American numbers, {@code +33 *** **12} otherwise — never the whole number. */
    public static String masked(String e164) {
        if (!isE164(e164)) {
            return "(invalid number)";
        }
        if (e164.startsWith("+1") && e164.length() == 12) {
            return "+1 %s *** **%s".formatted(e164.substring(2, 5), e164.substring(10));
        }
        return e164.substring(0, 3) + " *** **" + e164.substring(e164.length() - 2);
    }
}
