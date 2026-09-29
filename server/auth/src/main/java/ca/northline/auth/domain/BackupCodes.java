package ca.northline.auth.domain;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;
import java.util.stream.IntStream;

/**
 * "One of your 10 printed codes": ten single-use codes of 10 characters from an unambiguous alphabet, shown as
 * {@code abcde-fghij}. Only {@link #hash(String)} of the normalised code is stored.
 */
public final class BackupCodes {

    public static final int COUNT = 10;
    private static final char[] ALPHABET = "abcdefghjkmnpqrstuvwxyz23456789".toCharArray();
    private static final SecureRandom RANDOM = new SecureRandom();

    private BackupCodes() {}

    public static List<String> generate() {
        return IntStream.range(0, COUNT).mapToObj(_ -> one()).toList();
    }

    /** Lower-case, spaces and dashes removed — people type them however they like. */
    public static String normalize(String input) {
        return input.replaceAll("[\\s-]", "").toLowerCase(Locale.ROOT);
    }

    public static String hash(String code) {
        return OtpChallenge.hash("backup:" + normalize(code));
    }

    private static String one() {
        var sb = new StringBuilder(11);
        for (int i = 0; i < 10; i++) {
            if (i == 5) {
                sb.append('-');
            }
            sb.append(ALPHABET[RANDOM.nextInt(ALPHABET.length)]);
        }
        return sb.toString();
    }
}
