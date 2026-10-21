package ca.northline.sms.twilio;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * Twilio's request signature (S-157; Twilio's "Webhooks security" documentation): {@code X-Twilio-Signature} is the
 * Base64 HMAC-SHA1, keyed with the account's auth token, of the full URL Twilio requested followed by every POST
 * parameter's name and value, sorted by name. The URL must be the public one Twilio was given (configuration, never a
 * header), and the comparison is constant-time.
 */
public final class TwilioSignature {

    private TwilioSignature() {}

    public static String sign(String authToken, String url, Map<String, List<String>> params) {
        var data = new StringBuilder(url);
        new TreeMap<>(params)
                .forEach((name, values) ->
                        values.stream().sorted().forEach(v -> data.append(name).append(v)));
        try {
            var mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(authToken.getBytes(StandardCharsets.UTF_8), "HmacSHA1"));
            return Base64.getEncoder()
                    .encodeToString(mac.doFinal(data.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }

    public static boolean valid(
            String authToken, String url, Map<String, List<String>> params, @Nullable String signature) {
        if (signature == null || signature.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                sign(authToken, url, params).getBytes(StandardCharsets.US_ASCII),
                signature.strip().getBytes(StandardCharsets.US_ASCII));
    }
}
