package ca.northline.email;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.Locale;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.core.env.Environment;

/**
 * Signed, stateless unsubscribe tokens (S-13; shared with the worker's notifications in S-27 so both apps sign the same
 * way): {@code base64url("v1|userId|row|lang") + "." + base64url(HMAC-SHA256)} with {@code EMAIL_UNSUBSCRIBE_KEY}. They
 * don't expire (CASL: an unsubscribe link must keep working for at least 60 days after the email), carry no email
 * address, and only ever turn one Settings › Notifications email cell off — the api's unsubscribe endpoint reads them.
 * Rotating the key invalidates the links in emails already sent.
 */
@Slf4j
public final class UnsubscribeTokens {

    public static final String DEV_KEY = "northline-local-unsubscribe-key-not-for-production";

    private final SecretKeySpec key;

    public UnsubscribeTokens(String key) {
        this.key = new SecretKeySpec(key.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
    }

    /** The configured key; blank = the development key, refused under staging/prod and warned about under dev. */
    public static UnsubscribeTokens forEnvironment(@Nullable String configured, Environment environment) {
        if (configured == null || configured.isBlank()) {
            if (environment.matchesProfiles("staging | prod")) {
                throw new IllegalStateException(
                        "EMAIL_UNSUBSCRIBE_KEY is required under staging/prod" + " (docs/runbooks/email.md)");
            }
            if (environment.matchesProfiles("dev")) {
                log.warn("EMAIL_UNSUBSCRIBE_KEY is not set: unsubscribe links are signed with the development key");
            }
            return new UnsubscribeTokens(DEV_KEY);
        }
        return new UnsubscribeTokens(configured);
    }

    /** One user's email cell of one Settings › Notifications row, in the language the page answers in. */
    public record Subscription(String userId, String row, Locale locale) {}

    public String issue(String userId, String row, Locale locale) {
        var payload = String.join("|", "v1", userId, row, locale.toLanguageTag());
        var encoded = Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes(StandardCharsets.UTF_8));
        return encoded + "." + sign(encoded);
    }

    public Optional<Subscription> read(String token) {
        var dot = token.indexOf('.');
        if (dot < 1) {
            return Optional.empty();
        }
        var encoded = token.substring(0, dot);
        var signature = token.substring(dot + 1).getBytes(StandardCharsets.US_ASCII);
        if (!MessageDigest.isEqual(signature, sign(encoded).getBytes(StandardCharsets.US_ASCII))) {
            return Optional.empty();
        }
        try {
            var parts = new String(Base64.getUrlDecoder().decode(encoded), StandardCharsets.UTF_8).split("\\|", -1);
            if (parts.length != 4 || !parts[0].equals("v1")) {
                return Optional.empty();
            }
            return Optional.of(new Subscription(parts[1], parts[2], Locale.forLanguageTag(parts[3])));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }

    private String sign(String encoded) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(key);
            return Base64.getUrlEncoder()
                    .withoutPadding()
                    .encodeToString(mac.doFinal(encoded.getBytes(StandardCharsets.US_ASCII)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
