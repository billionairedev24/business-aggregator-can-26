package ca.northline.auth.signing;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.auth.signing.*} — how the token signing keys are kept. The provider and the active cloud key come
 * from {@code northline.kms.provider} / {@code northline.kms.key-id} ({@code KMS_PROVIDER}, {@code KMS_KEY_ID}).
 *
 * @param localDir {@code local} provider: directory of the JWK set file ({@code SIGNING_KEYS_DIR}); instances that
 *     share it share their keys
 * @param publishedKeyIds cloud providers: more keys to publish in the JWK set without signing with them — the next key
 *     before a rotation, the previous one after it ({@code KMS_PUBLISHED_KEY_IDS}, comma-separated)
 * @param publishAhead {@code local} rotation: how long a new key is published before it signs (longer than any JWK set
 *     cache of the api and BFF, which is 5 minutes)
 * @param retireAfter {@code local} rotation: how long a replaced key stays published (longer than the longest-lived
 *     token it signed: ID tokens, 30 minutes)
 * @param rotateEvery {@code local}: rotate automatically when the active key is this old; empty = only by command
 * @param region AWS region of the key ({@code KMS_REGION}); empty = the SDK's default chain ({@code AWS_REGION})
 * @param endpoint AWS KMS endpoint override ({@code KMS_ENDPOINT}), e.g. LocalStack; empty = the provider's endpoint
 */
@ConfigurationProperties("northline.auth.signing")
public record SigningProperties(
        Path localDir,
        @DefaultValue List<String> publishedKeyIds,
        @DefaultValue("10m") Duration publishAhead,
        @DefaultValue("1h") Duration retireAfter,
        @Nullable Duration rotateEvery,
        @Nullable String region,
        @Nullable String endpoint) {

    public SigningProperties {
        publishedKeyIds = publishedKeyIds.stream()
                .map(String::strip)
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
