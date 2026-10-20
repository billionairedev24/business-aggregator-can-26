package ca.northline.auth.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.HexFormat;

/**
 * S-154: what an email proves — a 6-digit code to type and a link token (256 random bits) to click. Only their
 * SHA-256 is stored ({@code auth.email_codes}); comparisons are constant-time.
 */
public final class EmailCode {

    private static final SecureRandom RANDOM = new SecureRandom();

    private EmailCode() {}

    /** A new pair: the code and the token as sent, never stored. */
    public record Issued(String code, String token) {
        public String codeHash() {
            return hashOfCode(code);
        }

        public String tokenHash() {
            return hashOfToken(token);
        }
    }

    public static Issued issue() {
        var code = "%06d".formatted(RANDOM.nextInt(1_000_000));
        var bytes = new byte[32];
        RANDOM.nextBytes(bytes);
        return new Issued(code, Base64.getUrlEncoder().withoutPadding().encodeToString(bytes));
    }

    public static String hashOfCode(String code) {
        return OtpChallenge.hash("email-code:" + code.strip());
    }

    public static String hashOfToken(String token) {
        return OtpChallenge.hash("email-token:" + token.strip());
    }

    /** Constant-time comparison of two hex hashes. */
    public static boolean sameHash(String a, String b) {
        return MessageDigest.isEqual(bytes(a), bytes(b));
    }

    private static byte[] bytes(String hex) {
        try {
            return HexFormat.of().parseHex(hex);
        } catch (IllegalArgumentException e) {
            return hex.getBytes(StandardCharsets.UTF_8);
        }
    }
}
