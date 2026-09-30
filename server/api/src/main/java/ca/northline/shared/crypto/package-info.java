/**
 * Envelope encryption of secrets the api has to keep (S-32: calendar refresh tokens): {@link
 * ca.northline.shared.crypto.SecretSealer}. Each value gets its own AES-256-GCM data key; the data key is wrapped by
 * the key service chosen with {@code northline.kms.provider} ({@code KMS_PROVIDER}, the S-7 switch): a local key
 * ({@code local}), AWS KMS, Google Cloud KMS or Azure Key Vault, with the key {@code KMS_ENCRYPTION_KEY_ID}. The key
 * never leaves the key service; only the wrapped data key and the ciphertext are stored.
 */
@NamedInterface("crypto")
@NullMarked
package ca.northline.shared.crypto;

import org.jspecify.annotations.NullMarked;
import org.springframework.modulith.NamedInterface;
