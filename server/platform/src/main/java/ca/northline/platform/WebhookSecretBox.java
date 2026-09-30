package ca.northline.platform;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.Optional;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.jspecify.annotations.Nullable;

/**
 * AES-256-GCM for partner webhook signing secrets ({@code developer.webhook_endpoints.secret_enc}, {@code nonce(12) ‖
 * ciphertext ‖ tag(16)}), shared by the api, which encrypts the {@code whsec_…} it shows once, and the worker, which
 * decrypts it to sign deliveries (S-33). The key is {@code WEBHOOK_SECRET_KEY} (base64 of 32 bytes); where none is
 * configured and the caller allows it ({@code local}, {@code test}) a fixed, public development key is used.
 */
public final class WebhookSecretBox {

    /** Key version written to {@code webhook_endpoints.secret_ref}. */
    public static final String KEY_REF = "db:aes-gcm:v1";

    /** "northline-dev-webhook-key-32byte" — never valid outside local and test. */
    static final String DEV_KEY = "bm9ydGhsaW5lLWRldi13ZWJob29rLWtleS0zMmJ5dGU=";

    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final SecretKeySpec key;

    private WebhookSecretBox(byte[] key) {
        if (key.length != 32) {
            throw new IllegalArgumentException("WEBHOOK_SECRET_KEY must be base64 of 32 bytes");
        }
        this.key = new SecretKeySpec(key, "AES");
    }

    /** The configured key, else the development key when {@code devKeyAllowed}, else empty (webhooks unavailable). */
    public static Optional<WebhookSecretBox> of(@Nullable String base64Key, boolean devKeyAllowed) {
        var encoded = base64Key != null && !base64Key.isBlank() ? base64Key.strip() : devKeyAllowed ? DEV_KEY : null;
        return Optional.ofNullable(encoded)
                .map(k -> new WebhookSecretBox(Base64.getDecoder().decode(k)));
    }

    public byte[] encrypt(String secret) {
        try {
            var nonce = new byte[NONCE];
            RANDOM.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, nonce));
            var sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the webhook secret", e);
        }
    }

    public String decrypt(byte[] encrypted) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE, key, new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(encrypted, 0, NONCE)));
            return new String(
                    cipher.doFinal(Arrays.copyOfRange(encrypted, NONCE, encrypted.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("Cannot decrypt the webhook secret (wrong WEBHOOK_SECRET_KEY?)", e);
        }
    }
}
