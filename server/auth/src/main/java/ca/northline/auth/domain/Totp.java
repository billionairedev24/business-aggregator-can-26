package ca.northline.auth.domain;

import dev.samstevens.totp.code.DefaultCodeGenerator;
import dev.samstevens.totp.exceptions.CodeGenerationException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.OptionalLong;
import java.util.stream.LongStream;

/**
 * RFC 6238 time-based codes (30 s steps, 6 digits, SHA-1 — what Google / Microsoft Authenticator and 1Password
 * expect). Accepts the previous, current and next step for clock drift and refuses a step that was already used.
 */
public final class Totp {

    public static final int PERIOD_SECONDS = 30;
    public static final int DIGITS = 6;
    private static final DefaultCodeGenerator GENERATOR = new DefaultCodeGenerator();

    private Totp() {}

    public static long step(Instant at) {
        return Math.floorDiv(at.getEpochSecond(), PERIOD_SECONDS);
    }

    public static String codeAt(String secret, long step) {
        try {
            return GENERATOR.generate(secret, step);
        } catch (CodeGenerationException e) {
            throw new IllegalArgumentException("Invalid TOTP secret", e);
        }
    }

    /**
     * The step the code belongs to, if it is valid now and newer than {@code lastUsedStep}.
     */
    public static OptionalLong verify(String secret, String code, Instant now, long lastUsedStep) {
        if (!code.matches(AuthMessages.SIX_DIGITS)) {
            return OptionalLong.empty();
        }
        var current = step(now);
        return LongStream.of(current, current - 1, current + 1)
                .filter(s -> s > lastUsedStep)
                .filter(s -> MessageDigest.isEqual(
                        codeAt(secret, s).getBytes(StandardCharsets.US_ASCII),
                        code.getBytes(StandardCharsets.US_ASCII)))
                .findFirst();
    }
}
