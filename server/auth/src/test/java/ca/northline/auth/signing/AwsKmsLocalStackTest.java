package ca.northline.auth.signing;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.kms.KmsClient;
import software.amazon.awssdk.services.kms.model.CreateKeyRequest;
import software.amazon.awssdk.services.kms.model.KeySpec;
import software.amazon.awssdk.services.kms.model.KeyUsageType;

/**
 * The AWS adapter against a real KMS API (LocalStack in Testcontainers): {@code ECC_NIST_P256} keys created with
 * {@code SIGN_VERIFY}, signatures made by the service, verified with the published public keys — the private key is
 * never seen by northline-auth.
 */
class AwsKmsLocalStackTest {

    @SuppressWarnings("resource")
    static final GenericContainer<?> LOCALSTACK = new GenericContainer<>("localstack/localstack:4.4")
            .withEnv("SERVICES", "kms")
            .withExposedPorts(4566)
            .waitingFor(Wait.forHttp("/_localstack/health").forPort(4566).forStatusCode(200));

    static KmsClient kms;

    @BeforeAll
    static void start() {
        LOCALSTACK.start();
        kms = KmsClient.builder()
                .endpointOverride(URI.create("http://" + LOCALSTACK.getHost() + ":" + LOCALSTACK.getMappedPort(4566)))
                .region(Region.CA_CENTRAL_1)
                .credentialsProvider(StaticCredentialsProvider.create(AwsBasicCredentials.create("test", "test")))
                .build();
    }

    @AfterAll
    static void stop() {
        kms.close();
        LOCALSTACK.stop();
    }

    private static String newKey() {
        return kms.createKey(CreateKeyRequest.builder()
                        .keySpec(KeySpec.ECC_NIST_P256)
                        .keyUsage(KeyUsageType.SIGN_VERIFY)
                        .description("northline-auth token signing (test)")
                        .build())
                .keyMetadata()
                .arn();
    }

    @Test
    void kmsSignsTokens_thatVerifyWithThePublishedKey() throws Exception {
        var keys = new RemoteSigningKeys(new AwsKmsKeyService(kms), newKey(), List.of());
        CloudKeyServicesTest.assertSignsVerifiableTokens(keys);
    }

    @Test
    void rotation_newKeySigns_previousStaysPublished() throws Exception {
        var previous = newKey();
        var next = newKey();
        var before = new RemoteSigningKeys(new AwsKmsKeyService(kms), previous, List.of(next));
        var after = new RemoteSigningKeys(new AwsKmsKeyService(kms), next, List.of(previous));

        CloudKeyServicesTest.assertSignsVerifiableTokens(after);
        assertThat(after.published()).containsExactlyInAnyOrderElementsOf(before.published());
        assertThat(after.active().keyId()).isNotEqualTo(before.active().keyId());
    }
}
