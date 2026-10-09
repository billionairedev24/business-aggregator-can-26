package ca.northline.platform;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * AES-256-GCM for partner webhook signing secrets ({@code developer.webhook_endpoints.secret_enc} and
 * {@code secret_prev_enc}, {@code nonce(12) ‖ ciphertext ‖ tag(16)}), shared by the api, which encrypts the
 * {@code whsec_…} it shows once, and the worker, which decrypts it to sign deliveries (S-33). The key is
 * {@code WEBHOOK_SECRET_KEY} (base64 of 32 bytes) named {@code WEBHOOK_SECRET_KEY_ID} (default {@code v1}); where none
 * is configured and the caller allows it ({@code local}, {@code test}) a fixed, public development key is used.
 *
 * <p>Engineering follow-ups (S-115 gap): the key can be rotated. Keys in {@code WEBHOOK_SECRET_PREVIOUS_KEYS}
 * ({@code id=base64,…}) still decrypt; the api's re-encryption job moves every row to the current key and records it in
 * {@code secret_ref} ({@link #keyRef()}, {@code db:aes-gcm:<id>}).
 */
public final class WebhookSecretBox {

    /** The key reference of rows written before key ids existed (the first key's, {@code v1}). */
    public static final String KEY_REF = "db:aes-gcm:v1";

    static final String REF_PREFIX = "db:aes-gcm:";
    static final String DEFAULT_KEY_ID = "v1";

    /** "northline-dev-webhook-key-32byte" — never valid outside local and test. */
    static final String DEV_KEY = "bm9ydGhsaW5lLWRldi13ZWJob29rLWtleS0zMmJ5dGU=";

    private final AesKeyRing keys;

    private WebhookSecretBox(AesKeyRing keys) {
        this.keys = keys;
    }

    /** The configured key, else the development key when {@code devKeyAllowed}, else empty (webhooks unavailable). */
    public static Optional<WebhookSecretBox> of(@Nullable String base64Key, boolean devKeyAllowed) {
        return of(base64Key, null, null, devKeyAllowed);
    }

    /**
     * @param keyId the current key's id ({@code WEBHOOK_SECRET_KEY_ID}), blank = {@code v1}
     * @param previousKeys keys that only decrypt ({@code WEBHOOK_SECRET_PREVIOUS_KEYS}, {@code id=base64,…})
     */
    public static Optional<WebhookSecretBox> of(
            @Nullable String base64Key, @Nullable String keyId, @Nullable String previousKeys, boolean devKeyAllowed) {
        var encoded = base64Key != null && !base64Key.isBlank() ? base64Key.strip() : devKeyAllowed ? DEV_KEY : null;
        var id = keyId == null || keyId.isBlank() ? DEFAULT_KEY_ID : keyId.strip();
        return Optional.ofNullable(encoded)
                .map(k -> new WebhookSecretBox(AesKeyRing.of("WEBHOOK_SECRET_KEY", id, k, previousKeys)));
    }

    /** What {@code secret_ref} says for a value encrypted now ({@code db:aes-gcm:<current id>}). */
    public String keyRef() {
        return REF_PREFIX + keys.currentId();
    }

    /** The key id a {@code secret_ref} names, or null when it names none. */
    public static @Nullable String keyIdOf(@Nullable String keyRef) {
        return keyRef != null && keyRef.startsWith(REF_PREFIX) ? keyRef.substring(REF_PREFIX.length()) : null;
    }

    public byte[] encrypt(String secret) {
        return keys.encrypt(secret);
    }

    public String decrypt(byte[] encrypted) {
        return decrypt(encrypted, null);
    }

    /** Opens a stored secret, trying the key its {@code secret_ref} names first. */
    public String decrypt(byte[] encrypted, @Nullable String keyRef) {
        return open(encrypted, keyRef).secret();
    }

    /** A stored secret and the {@code secret_ref} of the key that opened it. */
    public record Opened(String secret, String keyRef) {}

    public Opened open(byte[] encrypted, @Nullable String keyRef) {
        try {
            var opened = keys.decrypt(encrypted, keyIdOf(keyRef));
            return new Opened(opened.plain(), REF_PREFIX + opened.keyId());
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Cannot decrypt the webhook secret (WEBHOOK_SECRET_KEY / WEBHOOK_SECRET_PREVIOUS_KEYS?)", e);
        }
    }
}
