package ca.northline.developer.application;

import org.jspecify.annotations.Nullable;

/**
 * Outbound port: encrypts webhook signing secrets at rest (the worker decrypts to sign). AES-256-GCM with a versioned,
 * configured key ({@code secret_ref} names it); previous keys still open what they encrypted until the re-encryption
 * job ({@link WebhookKeyRotation}) has moved every row to the current key.
 */
public interface WebhookSecretCipher {

    /** Key version stored in {@code webhook_endpoints.secret_ref} for a value encrypted now. */
    String keyRef();

    byte[] encrypt(String secret);

    String decrypt(byte[] encrypted);

    /** A stored secret and the reference of the key that opened it (tried first: the one {@code keyRef} names). */
    Opened open(byte[] encrypted, @Nullable String keyRef);

    record Opened(String secret, String keyRef) {}
}
