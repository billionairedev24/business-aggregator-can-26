package ca.northline.auth.federation;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.interfaces.ECPrivateKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.locks.ReentrantLock;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.json.JsonMapper;

/**
 * Apple's "client secret": an ES256 JWT signed with the Sign in with Apple key (.p8), {@code iss} = team id,
 * {@code sub} = Services ID, {@code aud} = {@code https://appleid.apple.com}, valid at most 6 months. Generated here
 * and cached; a new one is made once less than a quarter of its life is left, so it never expires in use and a new key
 * (a restart with new {@code APPLE_KEY_ID} / {@code APPLE_PRIVATE_KEY}) takes effect at once. Nobody has to renew it by
 * hand.
 */
@Slf4j
public final class AppleClientSecret {

    static final String AUDIENCE = "https://appleid.apple.com";
    /** Apple refuses secrets valid for longer than 15777000 s (6 months). */
    static final Duration MAX_TTL = Duration.ofDays(180);

    private final String clientId;
    private final String teamId;
    private final String keyId;
    private final PrivateKey key;
    private final Duration ttl;
    private final Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private final ReentrantLock lock = new ReentrantLock();
    private @Nullable String current;
    private Instant expiresAt = Instant.EPOCH;

    AppleClientSecret(FederationProperties.Apple apple, Clock clock) {
        var problems = problems(apple);
        if (!problems.isEmpty()) {
            throw new IllegalStateException("Sign in with Apple is misconfigured: " + String.join("; ", problems));
        }
        this.clientId = Objects.requireNonNull(apple.clientId()).strip();
        this.teamId = Objects.requireNonNull(apple.teamId()).strip();
        this.keyId = Objects.requireNonNull(apple.keyId()).strip();
        this.key = parse(Objects.requireNonNull(apple.privateKey()));
        this.ttl = apple.clientSecretTtl().compareTo(MAX_TTL) > 0 ? MAX_TTL : apple.clientSecretTtl();
        this.clock = clock;
    }

    /** Every missing or unusable setting, for the start-up error (all at once). */
    static List<String> problems(FederationProperties.Apple apple) {
        var problems = new ArrayList<String>();
        if (blank(apple.teamId())) {
            problems.add("APPLE_TEAM_ID (the 10-character team id) is missing");
        }
        if (blank(apple.keyId())) {
            problems.add("APPLE_KEY_ID (the Sign in with Apple key id) is missing");
        }
        if (blank(apple.privateKey())) {
            problems.add("APPLE_PRIVATE_KEY (the .p8 key, PEM) is missing");
        } else {
            try {
                parse(Objects.requireNonNull(apple.privateKey()));
            } catch (IllegalArgumentException e) {
                problems.add("APPLE_PRIVATE_KEY is not a PKCS#8 EC P-256 key (" + e.getMessage() + ")");
            }
        }
        if (apple.clientSecretTtl().isNegative() || apple.clientSecretTtl().isZero()) {
            problems.add("northline.auth.federation.apple.client-secret-ttl must be positive");
        }
        return problems;
    }

    /** The secret to send with the next token request. */
    public String current() {
        lock.lock();
        try {
            var now = clock.instant();
            if (current == null || !now.isBefore(expiresAt.minus(ttl.dividedBy(4)))) {
                expiresAt = now.plus(ttl);
                current = sign(now, expiresAt);
                log.info("Apple client secret generated (key {}, valid until {})", keyId, expiresAt);
            }
            return current;
        } finally {
            lock.unlock();
        }
    }

    Instant expiresAt() {
        return expiresAt;
    }

    private String sign(Instant issuedAt, Instant expires) {
        var header = new LinkedHashMap<String, Object>();
        header.put("alg", "ES256");
        header.put("kid", keyId);
        var claims = new LinkedHashMap<String, Object>();
        claims.put("iss", teamId);
        claims.put("iat", issuedAt.getEpochSecond());
        claims.put("exp", expires.getEpochSecond());
        claims.put("aud", AUDIENCE);
        claims.put("sub", clientId);
        var input = b64(json.writeValueAsBytes(header)) + "." + b64(json.writeValueAsBytes(claims));
        try {
            var signature = Signature.getInstance("SHA256withECDSAinP1363Format"); // JWS wants R || S
            signature.initSign(key);
            signature.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + b64(signature.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing the Apple client secret failed", e);
        }
    }

    private static PrivateKey parse(String pem) {
        var body = pem.replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\n", "") // a PEM pasted into one line of an env file
                .replaceAll("\\s", "");
        try {
            var key = KeyFactory.getInstance("EC")
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)));
            if (!(key instanceof ECPrivateKey ec)
                    || ec.getParams().getCurve().getField().getFieldSize() != 256) {
                throw new IllegalArgumentException("not a P-256 key");
            }
            return key;
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException(e.getMessage() == null ? "unreadable" : e.getMessage(), e);
        }
    }

    private static String b64(byte[] bytes) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private static boolean blank(@Nullable String value) {
        return value == null || value.isBlank();
    }
}
