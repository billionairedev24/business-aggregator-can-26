package ca.northline.fulfilment.infra;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * The stand-in for object storage's presigned GET under {@code local}/{@code test} (a disk can't sign a URL): a link to
 * the api's {@code GET /api/v1/dev/proof-photos/{token}} whose token carries the object key and an expiry, signed with
 * HMAC-SHA256 under a key made at start-up (links die with a restart, as they should). The link is relative to the api's
 * origin ({@code /api/v1/…}): the consumer web reaches it through its own origin's proxy, the app against its api URL.
 */
@Component
@Profile({"local", "test"})
public class LocalProofLinks {

    static final String PATH = "/api/v1/dev/proof-photos/";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder B64D = Base64.getUrlDecoder();

    private final byte[] secret = new byte[32];
    private final Clock clock;

    LocalProofLinks(Clock clock) {
        this.clock = clock;
        new SecureRandom().nextBytes(secret);
    }

    URI url(String key, Duration ttl) {
        var expires = clock.instant().plus(ttl).getEpochSecond();
        var body = B64.encodeToString(key.getBytes(StandardCharsets.UTF_8)) + "." + expires;
        return URI.create(PATH + body + "." + B64.encodeToString(mac(body)));
    }

    /** The object key of a valid, unexpired token; empty for anything else. */
    public Optional<String> verify(String token) {
        var parts = token.split("\\.", -1);
        if (parts.length != 3) {
            return Optional.empty();
        }
        try {
            var body = parts[0] + "." + parts[1];
            if (!MessageDigest.isEqual(mac(body), B64D.decode(parts[2]))
                    || Long.parseLong(parts[1]) < clock.instant().getEpochSecond()) {
                return Optional.empty();
            }
            return Optional.of(new String(B64D.decode(parts[0]), StandardCharsets.UTF_8));
        } catch (IllegalArgumentException e) {
            return Optional.empty(); // not base64, not a number
        }
    }

    private byte[] mac(String body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret, "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }
}
