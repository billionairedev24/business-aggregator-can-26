package ca.northline.developer.integration;

import ca.northline.developer.application.WebhookSecretCipher;
import ca.northline.platform.WebhookSecretBox;
import ca.northline.shared.Conflict;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.stereotype.Component;

/**
 * AES-256-GCM for webhook signing secrets through the shared {@link WebhookSecretBox} (the worker decrypts with the same
 * box and key to sign deliveries, S-33). The key is {@code northline.developer.webhook-key} (base64, 32 bytes) from the
 * secrets manager; under {@code local}/{@code test} a fixed development key is used when none is configured. Other
 * profiles without a key refuse with 409 {@code webhooks_unavailable}.
 */
@Slf4j
@Component
@EnableConfigurationProperties(AesWebhookSecretCipher.Properties.class)
class AesWebhookSecretCipher implements WebhookSecretCipher {

    private final @Nullable WebhookSecretBox box;

    /**
     * @param webhookKey {@code WEBHOOK_SECRET_KEY}
     * @param webhookKeyId {@code WEBHOOK_SECRET_KEY_ID}: the current key's id (default {@code v1})
     * @param webhookPreviousKeys {@code WEBHOOK_SECRET_PREVIOUS_KEYS}: keys that only decrypt during a rotation
     */
    @ConfigurationProperties("northline.developer")
    record Properties(
            @Nullable String webhookKey,
            @Nullable String webhookKeyId,
            @Nullable String webhookPreviousKeys) {}

    AesWebhookSecretCipher(Properties properties, Environment environment) {
        this.box = WebhookSecretBox.of(
                        properties.webhookKey(),
                        properties.webhookKeyId(),
                        properties.webhookPreviousKeys(),
                        environment.acceptsProfiles(Profiles.of("local", "test")))
                .orElse(null);
        if (box == null) {
            log.warn("northline.developer.webhook-key is not set: webhook endpoints cannot be created");
        }
    }

    @Override
    public String keyRef() {
        return box().keyRef();
    }

    @Override
    public byte[] encrypt(String secret) {
        return box().encrypt(secret);
    }

    @Override
    public String decrypt(byte[] encrypted) {
        return box().decrypt(encrypted);
    }

    @Override
    public Opened open(byte[] encrypted, @Nullable String keyRef) {
        var opened = box().open(encrypted, keyRef);
        return new Opened(opened.secret(), opened.keyRef());
    }

    private WebhookSecretBox box() {
        if (box == null) {
            throw new Conflict("webhooks_unavailable", "Webhooks are not available yet.");
        }
        return box;
    }
}
