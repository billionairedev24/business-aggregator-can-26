package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.kms.*} — where token signing keys live (S-7): northline-auth signs with a key file under
 * {@code local} and inside AWS KMS, Google Cloud KMS or Azure Key Vault otherwise ({@code ca.northline.auth.signing},
 * docs/runbooks/key-rotation.md). TOTP and webhook secrets still use base64 keys from the environment.
 *
 * @param provider {@code local | aws | gcp | azure} ({@code KMS_PROVIDER})
 * @param keyId the signing key: key ARN (AWS), key version resource name (GCP) or versioned key identifier URL (Azure)
 *     ({@code KMS_KEY_ID})
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
