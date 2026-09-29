package ca.northline.developer.application;

/**
 * Outbound port: encrypts webhook signing secrets at rest (the worker decrypts to sign). AES-256-GCM with a configured
 * key today; a KMS adapter can replace it ({@code secret_ref} names the key version).
 */
public interface WebhookSecretCipher {

    /** Key version stored in {@code webhook_endpoints.secret_ref}. */
    String keyRef();

    byte[] encrypt(String secret);

    String decrypt(byte[] encrypted);
}
