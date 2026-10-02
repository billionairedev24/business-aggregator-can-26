package ca.northline.shared.crypto;

/**
 * Outbound port: seals a secret for storage and opens it again. {@code context} is bound to the ciphertext (AES-GCM
 * additional data, and the key service's encryption context where it has one): a value sealed for one row can't be
 * opened for another. Implemented by {@link EnvelopeSealer} over the configured key service.
 */
public interface SecretSealer {

    Sealed seal(String plaintext, String context);

    /** Throws {@link IllegalStateException} when the value was sealed for another context or the key is gone. */
    String open(Sealed sealed, String context);

    /**
     * Re-wraps the value's data key with the key service's current key (S-115, key rotation): unwrap with the key that
     * wrapped it, wrap again with today's key; the ciphertext is unchanged. Afterwards the old key (or key version)
     * can be disabled without losing the value.
     */
    Sealed rewrap(Sealed sealed, String context);

    /**
     * The reference a value sealed now would carry — the local key's fingerprint, the AWS key ARN, the Cloud KMS
     * primary key version, the Key Vault key version. A stored value whose {@code keyRef} differs was wrapped by an
     * older key and is due for {@link #rewrap}. Cached for a few minutes.
     */
    String currentKeyRef();

    /**
     * @param keyRef the key that wrapped the data key (a local key fingerprint, a KMS key ARN, a Cloud KMS key name or
     *     a versioned Key Vault key URL) — enough to open it after a rotation
     * @param wrappedKey the data key, wrapped by {@code keyRef}
     * @param ciphertext 12-byte nonce ‖ AES-256-GCM ciphertext and tag
     */
    @SuppressWarnings("ArrayRecordComponent") // stored as bytea; never compared
    record Sealed(String keyRef, byte[] wrappedKey, byte[] ciphertext) {}
}
