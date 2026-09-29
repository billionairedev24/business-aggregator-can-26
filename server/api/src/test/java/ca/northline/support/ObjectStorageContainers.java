package ca.northline.support;

import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.common.StorageSharedKeyCredential;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BucketInfo;
import com.google.cloud.storage.StorageOptions;
import java.net.URI;
import java.time.Duration;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;

/**
 * Object-storage emulators for the S-10 tests, one of each per test JVM (started on first use, stopped by Ryuk): RustFS
 * (the S3-compatible server the compose {@code storage} profile runs), fake-gcs-server (Google Cloud Storage JSON API)
 * and Azurite (Azure Blob). Each test uses its own bucket or keys.
 */
public final class ObjectStorageContainers {

    public static final String S3_ACCESS_KEY = "northline";
    public static final String S3_SECRET_KEY = "northline-test-secret";

    /** Azurite's well-known development account. */
    public static final String AZURITE_ACCOUNT = "devstoreaccount1";

    public static final String AZURITE_KEY =
            "Eby8vdM02xNOcqFlqUwJPLlmEtlCDXJ1OUzFT50uSRZ6IFsuFq2UVErCz4I6tq/K1SZFPTOtr/KBHBeksoGMGw==";

    private ObjectStorageContainers() {}

    /** {@code http://host:port} of RustFS's S3 API. */
    public static String s3Endpoint() {
        var c = RustFs.INSTANCE;
        return "http://%s:%d".formatted(c.getHost(), c.getMappedPort(9000));
    }

    /** {@code http://host:port} of fake-gcs-server. */
    public static String gcsEndpoint() {
        var c = FakeGcs.INSTANCE;
        return "http://%s:%d".formatted(c.getHost(), c.getMappedPort(4443));
    }

    /**
     * Azurite's blob account URL. The SDK reads the account from the path only for IP hosts (with a name such as
     * {@code localhost} it would take the account for the container), so {@code localhost} becomes {@code 127.0.0.1}.
     */
    public static String azureEndpoint() {
        var c = Azurite.INSTANCE;
        var host = "localhost".equals(c.getHost()) ? "127.0.0.1" : c.getHost();
        return "http://%s:%d/%s".formatted(host, c.getMappedPort(10000), AZURITE_ACCOUNT);
    }

    /** An S3 client for assertions (and bucket set-up) against RustFS. */
    public static S3Client s3Client() {
        return S3Client.builder()
                .endpointOverride(URI.create(s3Endpoint()))
                .region(Region.CA_CENTRAL_1)
                .forcePathStyle(true)
                .credentialsProvider(
                        StaticCredentialsProvider.create(AwsBasicCredentials.create(S3_ACCESS_KEY, S3_SECRET_KEY)))
                .build();
    }

    public static void createS3Bucket(String bucket) {
        try (var s3 = s3Client()) {
            if (s3.listBuckets().buckets().stream().noneMatch(b -> b.name().equals(bucket))) {
                s3.createBucket(b -> b.bucket(bucket));
            }
        }
    }

    public static void createGcsBucket(String bucket) throws Exception {
        try (var gcs = StorageOptions.http()
                .setHost(gcsEndpoint())
                .setProjectId("northline-emulator")
                .setCredentials(NoCredentials.getInstance())
                .build()
                .getService()) {
            if (gcs.get(bucket) == null) {
                gcs.create(BucketInfo.of(bucket));
            }
        }
    }

    public static void createAzureContainer(String container) {
        new BlobServiceClientBuilder()
                .endpoint(azureEndpoint())
                .credential(new StorageSharedKeyCredential(AZURITE_ACCOUNT, AZURITE_KEY))
                .buildClient()
                .getBlobContainerClient(container)
                .createIfNotExists();
    }

    private static final class RustFs {
        static final GenericContainer<?> INSTANCE = start(new GenericContainer<>("rustfs/rustfs:1.0.0")
                .withEnv("RUSTFS_ACCESS_KEY", S3_ACCESS_KEY)
                .withEnv("RUSTFS_SECRET_KEY", S3_SECRET_KEY)
                .withExposedPorts(9000)
                .waitingFor(Wait.forHttp("/health").forPort(9000).forStatusCode(200)));
    }

    private static final class FakeGcs {
        static final GenericContainer<?> INSTANCE = start(fakeGcs());

        /** {@code -public-host} = the host tests reach it on: signed URLs ({@code /<bucket>/<object>}) route by host. */
        private static GenericContainer<?> fakeGcs() {
            var container = new GenericContainer<>("fsouza/fake-gcs-server:1.52.2");
            return container
                    .withCommand(
                            "-scheme",
                            "http",
                            "-port",
                            "4443",
                            "-backend",
                            "memory",
                            "-public-host",
                            container.getHost())
                    .withExposedPorts(4443)
                    .waitingFor(Wait.forHttp("/storage/v1/b").forPort(4443).forStatusCode(200));
        }
    }

    private static final class Azurite {
        static final GenericContainer<?> INSTANCE =
                start(new GenericContainer<>("mcr.microsoft.com/azure-storage/azurite:3.35.0")
                        .withCommand("azurite-blob", "--blobHost", "0.0.0.0", "--skipApiVersionCheck", "--loose")
                        .withExposedPorts(10000)
                        .waitingFor(Wait.forListeningPort()));
    }

    private static GenericContainer<?> start(GenericContainer<?> container) {
        container.withStartupTimeout(Duration.ofMinutes(2)).start();
        return container;
    }
}
