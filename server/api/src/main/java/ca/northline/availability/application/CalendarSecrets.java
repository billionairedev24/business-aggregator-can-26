package ca.northline.availability.application;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/** Random values for OAuth state, PKCE and channel secrets; SHA-256 for what is stored and compared. */
final class CalendarSecrets {
    private CalendarSecrets() {}

    private static final SecureRandom RANDOM = new SecureRandom();

    /** 256 random bits, base64url without padding (43 characters: a valid PKCE verifier, Graph clientState ≤ 128). */
    static String random() {
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /** RFC 7636 S256: base64url(SHA-256(verifier)). */
    static String challenge(String verifier) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(digest(verifier));
    }

    static String sha256(String value) {
        return HexFormat.of().formatHex(digest(value));
    }

    /** Constant-time comparison of a presented secret with a stored hash. */
    static boolean matches(String presented, String storedHash) {
        return MessageDigest.isEqual(
                sha256(presented).getBytes(StandardCharsets.US_ASCII), storedHash.getBytes(StandardCharsets.US_ASCII));
    }

    private static byte[] digest(String value) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
