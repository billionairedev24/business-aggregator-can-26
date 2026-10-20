package ca.northline.loyalty.domain;

import java.security.SecureRandom;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * S-152: Invite a neighbour's codes — 8 letters and digits without look-alikes (no 0/O, 1/I/L), upper case. A code is
 * random, not derived from the person, so it says nothing about them.
 */
public final class ReferralCode {

    private static final String ALPHABET = "ABCDEFGHJKMNPQRSTUVWXYZ23456789";
    private static final SecureRandom RANDOM = new SecureRandom();
    public static final int LENGTH = 8;

    public static final String REQUIRED = "Enter an invite code.";
    public static final String UNKNOWN = "We don't know that invite code. Check it with the neighbour who sent it.";
    public static final String OWN = "That's your own invite code: share it with a neighbour instead.";
    public static final String NOT_NEW = "Invite codes are for new neighbours: your account isn't new any more.";
    public static final String SAME_HOUSEHOLD = "Invite codes are for other households, not your own.";
    public static final String TOO_MANY = "Too many tries. Try again in an hour.";
    public static final String ALREADY = "You've already used an invite code.";

    private ReferralCode() {}

    public static String next() {
        var b = new StringBuilder(LENGTH);
        for (int i = 0; i < LENGTH; i++) {
            b.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return b.toString();
    }

    /** What the person typed, as stored: upper case, spaces and dashes dropped; null when blank. */
    public static @Nullable String normalise(@Nullable String typed) {
        if (typed == null) {
            return null;
        }
        var s = typed.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        return s.isEmpty() ? null : s;
    }
}
