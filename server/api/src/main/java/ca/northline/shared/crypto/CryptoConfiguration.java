package ca.northline.shared.crypto;

import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import java.io.IOException;
import java.net.URI;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * The {@link SecretSealer} for {@code northline.kms.provider} ({@code KMS_PROVIDER}, S-7's switch): {@code local}
 * (default) wraps data keys with {@code KMS_LOCAL_KEY} — a fixed development key under {@code local}/{@code test};
 * under {@code dev} without it nothing can be sealed (409); refused under {@code staging}/{@code prod}; {@code aws} | {@code gcp} | {@code azure}
 * wrap them with {@code KMS_ENCRYPTION_KEY_ID} in the cloud key service (Terraform's {@code tokens} key; the api's
 * workload identity may encrypt and decrypt with it and nothing else). Only the chosen SDK client is created.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(CryptoConfiguration.CryptoProperties.class)
class CryptoConfiguration {

    static final String PROVIDER = "northline.kms.provider";

    /** Fixed key for {@code local} and {@code test} only ("northline-dev-envelope-key-32byt"). */
    static final String DEV_KEY = "bm9ydGhsaW5lLWRldi1lbnZlbG9wZS1rZXktMzJieXQ=";

    /**
     * @param keyId {@code KMS_ENCRYPTION_KEY_ID}: KMS key ARN (AWS), crypto key name (Google Cloud), versioned key URL
     *     (Azure)
     * @param localKey {@code KMS_LOCAL_KEY}: base64 of 32 bytes for {@code KMS_PROVIDER=local}
     * @param localPreviousKeys {@code KMS_LOCAL_PREVIOUS_KEYS}: comma-separated base64 keys that only unwrap (a local
     *     key rotation, S-115)
     * @param region {@code KMS_REGION} (AWS; default: the SDK's region chain)
     * @param endpoint {@code KMS_ENDPOINT} (AWS; LocalStack)
     */
    @ConfigurationProperties("northline.crypto")
    record CryptoProperties(
            @Nullable String keyId,
            @Nullable String localKey,
            @Nullable String localPreviousKeys,
            @Nullable String region,
            @Nullable String endpoint) {}

    @Bean
    SecretSealer secretSealer(KeyWrapper wrapper) {
        log.info("Secrets at rest: envelope encryption, data keys wrapped by kms provider={}", wrapper.provider());
        return new EnvelopeSealer(wrapper);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "local", matchIfMissing = true)
    static class Local {
        @Bean
        KeyWrapper localKeyWrapper(CryptoProperties props, Environment environment) {
            if (environment.matchesProfiles("staging | prod")) {
                throw new IllegalStateException("KMS_PROVIDER=local is not allowed under staging/prod: set KMS_PROVIDER"
                        + " to aws, gcp or azure and KMS_ENCRYPTION_KEY_ID (docs/runbooks/calendar-sync.md)");
            }
            var key = props.localKey();
            if (!StringUtils.hasText(key)) {
                if (!environment.matchesProfiles("local | test")) {
                    log.warn("KMS_PROVIDER=local without KMS_LOCAL_KEY: nothing can be sealed (calendar connections"
                            + " answer 409 encryption_unavailable). Set KMS_LOCAL_KEY or a cloud KMS_PROVIDER.");
                    return new KeyWrappers.Unavailable();
                }
                key = DEV_KEY;
            }
            var previous = StringUtils.hasText(props.localPreviousKeys())
                    ? Arrays.stream(props.localPreviousKeys().split(","))
                            .map(String::strip)
                            .filter(k -> !k.isEmpty())
                            .map(k -> Base64.getDecoder().decode(k))
                            .toList()
                    : List.<byte[]>of();
            return new KeyWrappers.Local(Base64.getDecoder().decode(key.strip()), previous);
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "aws")
    static class Aws {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        KmsClient envelopeKmsClient(CryptoProperties props) {
            var builder = KmsClient.builder();
            if (StringUtils.hasText(props.region())) {
                builder.region(Region.of(props.region()));
            }
            if (StringUtils.hasText(props.endpoint())) {
                builder.endpointOverride(URI.create(props.endpoint()));
            }
            return builder.build();
        }

        @Bean
        KeyWrapper awsKeyWrapper(KmsClient kms, CryptoProperties props) {
            return new KeyWrappers.Aws(kms, keyId(props, "aws"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "gcp")
    static class Gcp {
        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        KeyManagementServiceClient envelopeKmsClient() throws IOException {
            return KeyManagementServiceClient.create();
        }

        @Bean
        KeyWrapper gcpKeyWrapper(KeyManagementServiceClient kms, CryptoProperties props) {
            return new KeyWrappers.Gcp(kms, keyId(props, "gcp"));
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "azure")
    static class Azure {
        @Bean
        KeyWrapper azureKeyWrapper(CryptoProperties props) {
            var credential = new DefaultAzureCredentialBuilder().build();
            return new KeyWrappers.Azure(
                    id -> new CryptographyClientBuilder()
                            .keyIdentifier(id)
                            .credential(credential)
                            .buildClient(),
                    keyId(props, "azure"));
        }
    }

    private static String keyId(CryptoProperties props, String provider) {
        if (!StringUtils.hasText(props.keyId())) {
            throw new IllegalStateException("KMS_PROVIDER=" + provider
                    + " needs KMS_ENCRYPTION_KEY_ID (the api's envelope key, Terraform output)");
        }
        return props.keyId().strip();
    }
}
