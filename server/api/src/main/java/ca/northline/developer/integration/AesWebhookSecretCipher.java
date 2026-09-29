package ca.northline.developer.integration;

import ca.northline.developer.application.WebhookSecretCipher;
import ca.northline.shared.Conflict;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for webhook signing secrets ({@code nonce ‖ ciphertext}). The key is {@code northline.developer.
 * webhook-key} (base64, 32 bytes) from the secrets manager; under {@code local}/{@code test} a fixed development key is
 * used when none is configured. Other profiles without a key refuse with 409 {@code webhooks_unavailable}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AesWebhookSecretCipher.Properties.class)
class AesWebhookSecretCipher implements WebhookSecretCipher {

    private static final String DEV_KEY =
            "bm9ydGhsaW5lLWRldi13ZWJob29rLWtleS0zMmJ5dGU="; // "northline-dev-webhook-key-32byte"
    private static final int NONCE = 12;
    private static final int TAG_BITS = 128;

    private final SecureRandom random = new SecureRandom();
    private final @Nullable SecretKeySpec key;

    @ConfigurationProperties("northline.developer")
    record Properties(@Nullable String webhookKey) {}

    AesWebhookSecretCipher(Properties properties, Environment environment) {
        var configured = properties.webhookKey();
        var dev = environment.acceptsProfiles(Profiles.of("local", "test"));
        var encoded = configured != null && !configured.isBlank() ? configured : dev ? DEV_KEY : null;
        if (encoded == null) {
            log.warn("northline.developer.webhook-key is not set: webhook endpoints cannot be created");
        }
        this.key =
                encoded == null ? null : new SecretKeySpec(Base64.getDecoder().decode(encoded), "AES");
    }

    @Override
    public String keyRef() {
        return "db:aes-gcm:v1";
    }

    @Override
    public byte[] encrypt(String secret) {
        try {
            var nonce = new byte[NONCE];
            random.nextBytes(nonce);
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, key(), new GCMParameterSpec(TAG_BITS, nonce));
            var sealed = cipher.doFinal(secret.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(NONCE + sealed.length)
                    .put(nonce)
                    .put(sealed)
                    .array();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot encrypt the webhook secret", e);
        }
    }

    @Override
    public String decrypt(byte[] encrypted) {
        try {
            var cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(
                    Cipher.DECRYPT_MODE,
                    key(),
                    new GCMParameterSpec(TAG_BITS, Arrays.copyOfRange(encrypted, 0, NONCE)));
            return new String(
                    cipher.doFinal(Arrays.copyOfRange(encrypted, NONCE, encrypted.length)), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot decrypt the webhook secret", e);
        }
    }

    private SecretKeySpec key() {
        if (key == null) {
            throw new Conflict("webhooks_unavailable", "Webhooks are not available yet.");
        }
        return key;
    }
}
