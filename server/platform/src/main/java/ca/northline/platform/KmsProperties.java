package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.kms.*} — where token signing keys and data-encryption keys live. Only {@code local} exists today
 * (northline-auth generates its signing key at start-up; TOTP and webhook secrets use base64 keys from the
 * environment); AWS KMS, Google Cloud KMS and Azure Key Vault adapters come with backlog story S-7.
 *
 * @param provider {@code local | aws | gcp | azure} ({@code KMS_PROVIDER})
 * @param keyId key ARN (AWS), key resource name (GCP) or key identifier URL (Azure) ({@code KMS_KEY_ID})
 */
@ConfigurationProperties("northline.kms")
public record KmsProperties(
        @DefaultValue("local") Provider provider, @Nullable String keyId) {

    /** Which key service holds the keys. */
    public enum Provider {
        LOCAL,
        AWS,
        GCP,
        AZURE
    }
}
