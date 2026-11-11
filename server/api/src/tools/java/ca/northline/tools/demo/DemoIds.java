package ca.northline.tools.demo;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;

/**
 * Ids of the demo seed's rows: ULID-shaped text (26 Crockford base32 characters) that starts with {@link #PREFIX} and
 * is derived from a stable key ("merchant:bakery", "offer:bakery.sourdough"), so a re-run finds and updates the same
 * rows instead of adding new ones, and a reset finds every row the seed made by its prefix.
 */
final class DemoIds {

    /** Every demo id starts with this (a valid ULID time part, decoding to mid-2024). */
    static final String PREFIX = "01JDEM";

    /** The SQL LIKE pattern of a demo id. */
    static final String LIKE = PREFIX + "%";

    private static final char[] CROCKFORD = "0123456789ABCDEFGHJKMNPQRSTVWXYZ".toCharArray();
    private static final int RANDOM_CHARS = 26 - PREFIX.length();

    private DemoIds() {}

    static String of(String key) {
        byte[] hash;
        try {
            hash = MessageDigest.getInstance("SHA-256").digest(key.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
        var out = new StringBuilder(PREFIX);
        for (int i = 0; i < RANDOM_CHARS; i++) {
            out.append(CROCKFORD[(hash[i] & 0xff) % CROCKFORD.length]);
        }
        return out.toString();
    }

    static boolean isDemo(String id) {
        return id.startsWith(PREFIX);
    }
}
