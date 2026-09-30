package ca.northline.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.platform.PlatformAutoConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import software.amazon.awssdk.services.kms.KmsClient;

/** Which key wraps the data keys, from {@code KMS_PROVIDER} and the profile. */
class CryptoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(PlatformAutoConfiguration.class))
            .withUserConfiguration(CryptoConfiguration.class);

    @Test
    void local_isTheDefault_withTheDevelopmentKeyUnderTest() {
        runner.withPropertyValues("spring.profiles.active=test").run(context -> {
            var sealer = context.getBean(SecretSealer.class);
            assertThat(sealer.open(sealer.seal("x", "c"), "c")).isEqualTo("x");
            assertThat(sealer.seal("x", "c").keyRef()).startsWith("local:");
        });
    }

    @Test
    void local_isRefusedUnderStagingAndProd() {
        runner.withPropertyValues("spring.profiles.active=prod")
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("KMS_PROVIDER=local is not allowed under staging/prod"));
    }

    @Test
    void local_underDev_withoutAKey_startsButSealsNothing() {
        runner.withPropertyValues("spring.profiles.active=dev").run(context -> {
            assertThat(context).hasNotFailed();
            assertThat(((EnvelopeSealer) context.getBean(SecretSealer.class)).provider())
                    .contains("no KMS_LOCAL_KEY");
        });
        runner.withPropertyValues(
                        "spring.profiles.active=dev", "northline.crypto.local-key=" + CryptoConfiguration.DEV_KEY)
                .run(context -> assertThat(((EnvelopeSealer) context.getBean(SecretSealer.class)).provider())
                        .isEqualTo("local"));
    }

    @Test
    void cloudProviders_needTheEncryptionKeyId() {
        runner.withPropertyValues("northline.kms.provider=aws", "northline.crypto.region=ca-central-1")
                .withBean(
                        KmsClient.class,
                        () -> KmsClient.builder()
                                .region(software.amazon.awssdk.regions.Region.CA_CENTRAL_1)
                                .build())
                .run(context -> assertThat(context)
                        .hasFailed()
                        .getFailure()
                        .rootCause()
                        .hasMessageContaining("KMS_ENCRYPTION_KEY_ID"));
        runner.withPropertyValues(
                        "northline.kms.provider=aws",
                        "northline.crypto.region=ca-central-1",
                        "northline.crypto.key-id=arn:aws:kms:ca-central-1:000000000000:key/test")
                .run(context -> {
                    assertThat(context).hasSingleBean(KmsClient.class);
                    assertThat(((EnvelopeSealer) context.getBean(SecretSealer.class)).provider())
                            .isEqualTo("aws");
                });
    }
}
