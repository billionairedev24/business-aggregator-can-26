package ca.northline.auth.persistence;

import ca.northline.auth.application.AuthProperties;
import ca.northline.platform.AesKeyRing;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * AES-256-GCM for TOTP secrets at rest ({@code auth.totp_secrets.secret_enc} = 12-byte IV ‖ ciphertext+tag). The key
 * comes from {@code northline.auth.totp-key} (secrets manager in prod; a fixed dev key under {@code local}/{@code test}).
 *
 * <p>Engineering follow-ups (S-115 gap): the key is versioned — {@code TOTP_KEY_ID} names it ({@code key_id} on each
 * row, V350) and {@code TOTP_PREVIOUS_KEYS} ({@code id=base64,…}) keeps older keys for decryption while {@link
 * TotpKeyRotation} re-encrypts every secret with the current one.
 */
class SecretCipher {

    /** The bean: the configured key and its id, the previous keys. */
    @Configuration(proxyBeanMethods = false)
    @EnableConfigurationProperties(SecretCipher.Keys.class)
    static class Config {
        @Bean
        SecretCipher secretCipher(AuthProperties props, Keys keys) {
            return SecretCipher.of(props.totpKey(), keys);
        }
    }

    /**
     * @param totpKeyId {@code TOTP_KEY_ID}: the current key's id
     * @param totpPreviousKeys {@code TOTP_PREVIOUS_KEYS}: keys that only decrypt during a rotation
     */
    @ConfigurationProperties("northline.auth")
    record Keys(
            @DefaultValue("v1") String totpKeyId, @Nullable String totpPreviousKeys) {}

    /** A secret and the id of the key that opened it. */
    record Opened(String secret, String keyId) {}

    private final AesKeyRing keys;

    private SecretCipher(AesKeyRing keys) {
        this.keys = keys;
    }

    /** A cipher over {@code totpKey} and the given ids (a rotation in tests and tools). */
    static SecretCipher of(String totpKey, Keys keys) {
        return new SecretCipher(AesKeyRing.of("TOTP_KEY", keys.totpKeyId(), totpKey, keys.totpPreviousKeys()));
    }

    /** The id stored next to a value encrypted now. */
    String keyId() {
        return keys.currentId();
    }

    byte[] encrypt(String plain) {
        return keys.encrypt(plain);
    }

    String decrypt(byte[] box, @Nullable String keyId) {
        return open(box, keyId).secret();
    }

    Opened open(byte[] box, @Nullable String keyId) {
        try {
            var opened = keys.decrypt(box, keyId);
            return new Opened(opened.plain(), opened.keyId());
        } catch (IllegalStateException e) {
            throw new IllegalStateException(
                    "Decryption failed (wrong northline.auth.totp-key / TOTP_PREVIOUS_KEYS?)", e);
        }
    }
}
