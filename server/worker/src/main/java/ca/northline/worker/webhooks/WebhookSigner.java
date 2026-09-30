package ca.northline.worker.webhooks;

import java.nio.charset.StandardCharsets;
import java.security.InvalidKeyException;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * The {@code Northline-Signature} header, Stripe's scheme: {@code t=<unix seconds>,v1=<hex HMAC-SHA256>} where the
 * HMAC key is the endpoint's whole secret ({@code whsec_…}, UTF-8) and the signed text is {@code <t>.<raw body>}.
 * During a secret rotation's overlap there is one {@code v1} per valid secret (new first); a receiver accepts the
 * delivery when any of them matches and the timestamp is recent. {@link #verify} is the reference check for
 * receivers (docs/runbooks/webhooks.md).
 */
public final class WebhookSigner {

    public static final String HEADER = "Northline-Signature";
    /** How old a signature receivers should still accept (replay protection). */
    public static final Duration TOLERANCE = Duration.ofMinutes(5);

    private WebhookSigner() {}

    /** The header value for {@code body} sent at {@code at}, one {@code v1} per secret (at least one). */
    public static String header(Instant at, byte[] body, List<String> secrets) {
        if (secrets.isEmpty()) {
            throw new IllegalArgumentException("no signing secret");
        }
        var t = at.getEpochSecond();
        return "t=" + t + secrets.stream().map(s -> ",v1=" + hmac(s, t, body)).collect(Collectors.joining());
    }

    /** Whether {@code header} signs {@code body} with {@code secret}, at most {@code tolerance} before {@code now}. */
    public static boolean verify(String header, byte[] body, String secret, Instant now, Duration tolerance) {
        Long t = null;
        var signatures = new ArrayList<String>();
        for (var part : header.split(",")) {
            var kv = part.strip().split("=", 2);
            if (kv.length != 2) {
                continue;
            }
            switch (kv[0]) {
                case "t" -> t = kv[1].matches("\\d{1,12}") ? Long.parseLong(kv[1]) : null;
                case "v1" -> signatures.add(kv[1]);
                default -> {
                    /* unknown schemes are ignored, as Stripe's are */
                }
            }
        }
        if (t == null || Math.abs(now.getEpochSecond() - t) > tolerance.toSeconds()) {
            return false;
        }
        var expected = hmac(secret, t, body).getBytes(StandardCharsets.US_ASCII);
        return signatures.stream()
                .anyMatch(s -> MessageDigest.isEqual(expected, s.getBytes(StandardCharsets.US_ASCII)));
    }

    private static String hmac(String secret, long t, byte[] body) {
        try {
            var mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            mac.update((t + ".").getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(mac.doFinal(body));
        } catch (NoSuchAlgorithmException | InvalidKeyException e) {
            throw new IllegalStateException(e);
        }
    }
}
