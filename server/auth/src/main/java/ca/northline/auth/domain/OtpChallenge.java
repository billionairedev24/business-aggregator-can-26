package ca.northline.auth.domain;

import java.io.Serializable;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;

/**
 * A one-time phone code (registration, validation-rules.md: 6 digits, resend after 45 s, voice fallback). Immutable:
 * every operation returns the next state. Only a SHA-256 of the code is kept.
 *
 * @param codeHash SHA-256 (hex) of the current code
 * @param channel how the current code was delivered
 * @param sentAt when the current code was sent (the resend cool-down counts from here)
 * @param expiresAt when the current code stops working
 * @param failedAttempts wrong guesses against the current code
 */
public record OtpChallenge(String codeHash, Channel channel, Instant sentAt, Instant expiresAt, int failedAttempts)
        implements Serializable {

    /** Delivery channel. SMS first; "Call me instead" reads the code out by voice. */
    public enum Channel {
        SMS,
        VOICE
    }

    /** Result of checking a submitted code. */
    public sealed interface Check {
        record Verified() implements Check {}

        record Wrong(OtpChallenge next) implements Check {}

        record Expired() implements Check {}

        record Locked(OtpChallenge next) implements Check {}
    }

    public static OtpChallenge issue(String code, Channel channel, Instant now, Duration ttl) {
        return new OtpChallenge(hash(code), channel, now, now.plus(ttl), 0);
    }

    /** Seconds until another code may be sent; 0 when it may be sent now. */
    public long secondsUntilResend(Instant now, Duration cooldown) {
        var wait = Duration.between(now, sentAt.plus(cooldown));
        return wait.isNegative() || wait.isZero() ? 0 : (wait.toMillis() + 999) / 1000;
    }

    public Check check(String code, Instant now, int maxAttempts) {
        if (failedAttempts >= maxAttempts) {
            return new Check.Locked(this);
        }
        if (!now.isBefore(expiresAt)) {
            return new Check.Expired();
        }
        if (MessageDigest.isEqual(
                hash(code).getBytes(StandardCharsets.US_ASCII), codeHash.getBytes(StandardCharsets.US_ASCII))) {
            return new Check.Verified();
        }
        var next = new OtpChallenge(codeHash, channel, sentAt, expiresAt, failedAttempts + 1);
        return next.failedAttempts >= maxAttempts ? new Check.Locked(next) : new Check.Wrong(next);
    }

    static String hash(String value) {
        try {
            var digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
