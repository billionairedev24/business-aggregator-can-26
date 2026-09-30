package ca.northline.shared.crypto;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.URI;
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
 * The AWS adapter against the real KMS API (LocalStack in Testcontainers, the image S-7 pinned): a symmetric key wraps
 * each data key with the row id as encryption context; a different context is refused by KMS itself.
 */
class AwsKmsSealerLocalStackTest {

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

    @Test
    void kmsWrapsTheDataKey_andChecksTheContext() {
        var keyArn = kms.createKey(CreateKeyRequest.builder()
                        .keySpec(KeySpec.SYMMETRIC_DEFAULT)
                        .keyUsage(KeyUsageType.ENCRYPT_DECRYPT)
                        .description("northline-api tokens (test)")
                        .build())
                .keyMetadata()
                .arn();
        var sealer = new EnvelopeSealer(new KeyWrappers.Aws(kms, keyArn));

        var sealed = sealer.seal("1//fake-google-refresh-token", "link-aws");
        assertThat(sealed.keyRef()).isEqualTo(keyArn);
        assertThat(sealer.open(sealed, "link-aws")).isEqualTo("1//fake-google-refresh-token");
        assertThatThrownBy(() -> sealer.open(sealed, "another-link")).isInstanceOf(RuntimeException.class);
    }
}
