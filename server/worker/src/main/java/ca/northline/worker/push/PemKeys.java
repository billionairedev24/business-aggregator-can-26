package ca.northline.worker.push;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.util.Base64;

/** PKCS#8 PEM keys and compact JWS signing for the providers' credentials (no JOSE library in the worker). */
final class PemKeys {

    private static final Base64.Encoder URL = Base64.getUrlEncoder().withoutPadding();

    private PemKeys() {}

    /**
     * A {@code -----BEGIN PRIVATE KEY-----} block: the APNs {@code .p8} key ({@code EC}) or a Google service account's
     * {@code private_key} ({@code RSA}). Escaped {@code \n} (a key pasted into one environment variable) are accepted.
     */
    static PrivateKey privateKey(String pem, String algorithm) {
        var base64 = pem.replace("\\n", "\n")
                .replaceAll("-----(BEGIN|END) PRIVATE KEY-----", "")
                .replaceAll("\\s", "");
        try {
            return KeyFactory.getInstance(algorithm)
                    .generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Not a PKCS#8 " + algorithm + " private key (PEM)", e);
        }
    }

    /**
     * A compact JWS: {@code ES256} (the signature is {@code R || S}, as JOSE wants, not DER) or {@code RS256}.
     *
     * @param header / claims JSON objects
     */
    static String sign(String header, String claims, PrivateKey key) {
        var input = encode(header) + "." + encode(claims);
        try {
            var signer = Signature.getInstance(
                    key.getAlgorithm().equals("EC") ? "SHA256withECDSAinP1363Format" : "SHA256withRSA");
            signer.initSign(key);
            signer.update(input.getBytes(StandardCharsets.US_ASCII));
            return input + "." + URL.encodeToString(signer.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not sign a provider token", e);
        }
    }

    private static String encode(String json) {
        return URL.encodeToString(json.getBytes(StandardCharsets.UTF_8));
    }
}
