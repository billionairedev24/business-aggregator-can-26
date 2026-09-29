package ca.northline.developer.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Secrets shown once: API keys ({@code nl_live_…}, stored as SHA-256) and webhook signing secrets ({@code whsec_…},
 * stored encrypted because the worker must sign with them).
 */
public final class Secrets {
    private Secrets() {}

    public static final String API_KEY_PREFIX = "nl_live_";
    public static final String WEBHOOK_PREFIX = "whsec_";
    /** Characters of the key kept for display ("nl_live_Ab3x…"). */
    public static final int SHOWN = 12;

    private static final SecureRandom RANDOM = new SecureRandom();

    public static String apiKey() {
        return API_KEY_PREFIX + random(24);
    }

    public static String webhookSecret() {
        return WEBHOOK_PREFIX + random(24);
    }

    public static byte[] sha256(String secret) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(secret.getBytes(StandardCharsets.UTF_8));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    public static String shown(String secret) {
        return secret.substring(0, Math.min(SHOWN, secret.length()));
    }

    private static String random(int bytes) {
        var buffer = new byte[bytes];
        RANDOM.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }
}
