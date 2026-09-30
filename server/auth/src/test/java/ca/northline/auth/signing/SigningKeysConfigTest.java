package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.google.cloud.kms.v1.CryptoKeyVersion.CryptoKeyVersionAlgorithm;
import com.google.cloud.kms.v1.KeyManagementServiceClient;
import com.google.cloud.kms.v1.PublicKey;
import java.nio.file.Path;
import java.time.Clock;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import software.amazon.awssdk.core.SdkBytes;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.GetPublicKeyResponse;
import software.amazon.awssdk.services.kms.model.KeySpec;

/** {@code northline.kms.provider} alone decides which key store signs; misconfiguration stops the start-up. */
class SigningKeysConfigTest {

    @TempDir
    Path dir;

    private ApplicationContextRunner runner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(SigningKeysConfig.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues("northline.auth.signing.local-dir=" + dir);
    }

    @Test
    void defaultIsTheLocalKeyFile() {
        runner().run(ctx -> {
            assertThat(ctx).hasSingleBean(LocalFileSigningKeys.class).hasSingleBean(JwtEncoder.class);
            assertThat(ctx).doesNotHaveBean(LocalKeyRotationJob.class);
            assertThat(dir.resolve(LocalFileSigningKeys.FILE)).exists();
        });
    }

    @Test
    void localRotationJob_onlyWhenAPeriodIsSet() {
        runner().withPropertyValues("northline.auth.signing.rotate-every=90d")
                .run(ctx -> assertThat(ctx).hasSingleBean(LocalKeyRotationJob.class));
        runner().withPropertyValues("northline.auth.signing.rotate-every=")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(LocalKeyRotationJob.class));
    }

    @Test
    void local_isRefusedUnderProdAndStaging() {
        for (var profile : new String[] {"prod", "staging"}) {
            runner().withPropertyValues("spring.profiles.active=" + profile, "northline.kms.provider=local")
                    .run(ctx -> assertThat(ctx)
                            .getFailure()
                            .rootCause()
                            .hasMessageContaining("KMS_PROVIDER=local is not allowed under staging/prod"));
        }
    }

    @Test
    void aws_signsThroughKms() {
        var key = new CloudKeyServicesTest.FakeKmsKey();
        var kms = mock(KmsClient.class);
        when(kms.getPublicKey(any(software.amazon.awssdk.services.kms.model.GetPublicKeyRequest.class)))
                .thenReturn(GetPublicKeyResponse.builder()
                        .keySpec(KeySpec.ECC_NIST_P256)
                        .publicKey(SdkBytes.fromByteArray(key.pair.getPublic().getEncoded()))
                        .build());
        runner().withBean(KmsClient.class, () -> kms)
                .withPropertyValues(
                        "northline.kms.provider=aws", "northline.kms.key-id=arn:aws:kms:ca-central-1:1:key/k")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SigningKeys.class).doesNotHaveBean(LocalFileSigningKeys.class);
                    assertThat(ctx.getBean(SigningKeys.class)).isInstanceOf(RemoteSigningKeys.class);
                    assertThat(dir.resolve(LocalFileSigningKeys.FILE)).doesNotExist();
                });
    }

    @Test
    void gcp_signsThroughCloudKms() {
        var key = new CloudKeyServicesTest.FakeKmsKey();
        var kms = mock(KeyManagementServiceClient.class);
        when(kms.getPublicKey(any(String.class)))
                .thenReturn(PublicKey.newBuilder()
                        .setPem(key.pem())
                        .setAlgorithm(CryptoKeyVersionAlgorithm.EC_SIGN_P256_SHA256)
                        .build());
        runner().withBean(KeyManagementServiceClient.class, () -> kms)
                .withPropertyValues(
                        "northline.kms.provider=gcp", "northline.kms.key-id=projects/p/.../cryptoKeyVersions/1")
                .run(ctx -> assertThat(ctx.getBean(SigningKeys.class)).isInstanceOf(RemoteSigningKeys.class));
    }

    @Test
    void azure_signsThroughKeyVault() {
        var service = mock(AzureKeyVaultKeyService.class);
        var key = new CloudKeyServicesTest.FakeKmsKey();
        when(service.name()).thenReturn("azure");
        when(service.publicKey(any())).thenReturn((java.security.interfaces.ECPublicKey) key.pair.getPublic());
        runner().withBean(AzureKeyVaultKeyService.class, () -> service)
                .withPropertyValues(
                        "northline.kms.provider=azure",
                        "northline.kms.key-id=https://nl.vault.azure.net/keys/token-signing/1")
                .run(ctx -> assertThat(ctx.getBean(SigningKeys.class)).isInstanceOf(RemoteSigningKeys.class));
    }

    @Test
    void cloudProviderWithoutKeyId_failsAtStartUp() {
        runner().withBean(KmsClient.class, () -> mock(KmsClient.class))
                .withPropertyValues("northline.kms.provider=aws")
                .run(ctx -> assertThat(ctx).getFailure().rootCause().hasMessageContaining("needs KMS_KEY_ID"));
    }
}
