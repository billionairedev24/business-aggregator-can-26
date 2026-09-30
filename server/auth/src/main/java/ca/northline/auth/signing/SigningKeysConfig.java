package ca.northline.auth.signing;

import ca.northline.platform.KmsProperties;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.security.keyvault.keys.cryptography.CryptographyClientBuilder;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import java.io.IOException;
import java.net.URI;
import java.time.Clock;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;

/**
 * Wires {@link SigningKeys} from {@code northline.kms.provider} ({@code KMS_PROVIDER}): {@code local} (default) = JWK set
 * file in {@code SIGNING_KEYS_DIR}; {@code aws} | {@code gcp} | {@code azure} = the cloud key service signs, with the key
 * {@code KMS_KEY_ID}. The cloud SDK clients are only created for the chosen provider (tests replace them with their
 * own beans). {@code staging} and {@code prod} refuse {@code local}: their keys must never sit on a disk.
 */
@Slf4j
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties({SigningProperties.class, KmsProperties.class})
public class SigningKeysConfig {

    static final String PROVIDER = "northline.kms.provider";
    static final String ROTATION_JOB = "'${northline.auth.signing.rotate-every:}' != ''";

    /** {@code /oauth2/jwks} and the auth server's own decoder: public keys only, read on every call (rotation). */
    @Bean
    JWKSource<SecurityContext> jwkSource(SigningKeys keys) {
        return (selector, _) -> selector.select(new JWKSet(List.<JWK>copyOf(keys.published())));
    }

    @Bean
    JwtEncoder jwtEncoder(SigningKeys keys) {
        return new KeyStoreJwtEncoder(keys);
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "local", matchIfMissing = true)
    static class Local {

        @Bean
        LocalFileSigningKeys signingKeys(SigningProperties props, Clock clock, Environment environment) {
            if (environment.matchesProfiles("staging | prod")) {
                throw new IllegalStateException("KMS_PROVIDER=local is not allowed under staging/prod: set KMS_PROVIDER"
                        + " to aws, gcp or azure and KMS_KEY_ID (docs/runbooks/key-rotation.md)");
            }
            var keys = new LocalFileSigningKeys(props.localDir(), clock, props.publishAhead(), props.retireAfter());
            log.info("Signing keys: provider=local file={} keys={}", keys.file(), keys.describe());
            return keys;
        }

        @Bean
        @ConditionalOnExpression(ROTATION_JOB)
        LocalKeyRotationJob localKeyRotationJob(LocalFileSigningKeys keys, SigningProperties props) {
            return new LocalKeyRotationJob(keys, props);
        }

        /** Only when the rotation job is on. */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnExpression(ROTATION_JOB)
        @EnableScheduling
        static class Scheduling {}
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "aws")
    static class Aws {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        KmsClient kmsClient(SigningProperties props) {
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
        SigningKeys signingKeys(KmsClient kms, KmsProperties kmsProps, SigningProperties props) {
            return new RemoteSigningKeys(new AwsKmsKeyService(kms), keyId(kmsProps), props.publishedKeyIds());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "gcp")
    static class Gcp {

        @Bean(destroyMethod = "close")
        @ConditionalOnMissingBean
        KeyManagementServiceClient keyManagementServiceClient() throws IOException {
            return KeyManagementServiceClient.create();
        }

        @Bean
        SigningKeys signingKeys(KeyManagementServiceClient kms, KmsProperties kmsProps, SigningProperties props) {
            return new RemoteSigningKeys(new GcpKmsKeyService(kms), keyId(kmsProps), props.publishedKeyIds());
        }
    }

    @Configuration(proxyBeanMethods = false)
    @ConditionalOnProperty(name = PROVIDER, havingValue = "azure")
    static class Azure {

        @Bean
        @ConditionalOnMissingBean
        AzureKeyVaultKeyService azureKeyVaultKeyService() {
            var credential = new DefaultAzureCredentialBuilder().build();
            return new AzureKeyVaultKeyService(keyId -> new CryptographyClientBuilder()
                    .keyIdentifier(keyId)
                    .credential(credential)
                    .buildClient());
        }

        @Bean
        SigningKeys signingKeys(AzureKeyVaultKeyService vault, KmsProperties kmsProps, SigningProperties props) {
            return new RemoteSigningKeys(vault, keyId(kmsProps), props.publishedKeyIds());
        }
    }

    private static String keyId(KmsProperties kms) {
        var keyId = kms.keyId();
        if (!StringUtils.hasText(keyId)) {
            throw new IllegalStateException("KMS_PROVIDER=" + kms.provider() + " needs KMS_KEY_ID (the signing key)");
        }
        return keyId.strip();
    }
}
