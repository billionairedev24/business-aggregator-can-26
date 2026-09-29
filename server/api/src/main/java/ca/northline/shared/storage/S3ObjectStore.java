package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.AwsCredentialsProvider;
import software.amazon.awssdk.auth.credentials.DefaultCredentialsProvider;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.checksums.RequestChecksumCalculation;
import software.amazon.awssdk.core.checksums.ResponseChecksumValidation;
import software.amazon.awssdk.core.sync.RequestBody;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.S3Configuration;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;
import software.amazon.awssdk.services.s3.model.ServerSideEncryption;
import software.amazon.awssdk.services.s3.presigner.S3Presigner;

/**
 * AWS S3 and S3-compatible servers (RustFS, MinIO) through the AWS SDK v2. Credentials: {@code STORAGE_ACCESS_KEY} /
 * {@code STORAGE_SECRET_KEY} when set (local servers), otherwise the default chain (IRSA / EKS Pod Identity, instance
 * profile). {@code STORAGE_ENCRYPTION_KEY} = SSE-KMS with that key; otherwise the bucket's default encryption (SSE-S3
 * on AWS since 2023). With a custom endpoint the SDK only sends checksums when an operation requires them, which
 * S3-compatible servers do not all support.
 */
@Slf4j
final class S3ObjectStore implements ObjectStore, AutoCloseable {

    private final S3Client s3;
    private final S3Presigner presigner;
    private final String bucket;
    private final @Nullable String kmsKeyId;

    S3ObjectStore(S3Client s3, S3Presigner presigner, String bucket, @Nullable String kmsKeyId) {
        this.s3 = s3;
        this.presigner = presigner;
        this.bucket = bucket;
        this.kmsKeyId = StringUtils.hasText(kmsKeyId) ? kmsKeyId : null;
    }

    static S3ObjectStore create(StorageProperties props) {
        var region = Region.of(StringUtils.hasText(props.region()) ? props.region() : "ca-central-1");
        var credentials = credentials(props);
        var serviceConfig = S3Configuration.builder()
                .pathStyleAccessEnabled(props.pathStyle())
                .build();
        var client = S3Client.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        var presigner = S3Presigner.builder()
                .region(region)
                .credentialsProvider(credentials)
                .serviceConfiguration(serviceConfig);
        var endpoint = props.endpoint();
        if (StringUtils.hasText(endpoint)) {
            client.endpointOverride(URI.create(endpoint))
                    .requestChecksumCalculation(RequestChecksumCalculation.WHEN_REQUIRED)
                    .responseChecksumValidation(ResponseChecksumValidation.WHEN_REQUIRED);
            presigner.endpointOverride(URI.create(endpoint));
        }
        var bucket = StorageSettings.bucket(props);
        log.info(
                "Object storage: s3 bucket={} region={} endpoint={} pathStyle={} sse={}",
                bucket,
                region,
                StringUtils.hasText(endpoint) ? endpoint : "aws",
                props.pathStyle(),
                StringUtils.hasText(props.encryptionKey()) ? "kms" : "bucket-default");
        return new S3ObjectStore(client.build(), presigner.build(), bucket, props.encryptionKey());
    }

    private static AwsCredentialsProvider credentials(StorageProperties props) {
        return StringUtils.hasText(props.accessKey()) && StringUtils.hasText(props.secretKey())
                ? StaticCredentialsProvider.create(AwsBasicCredentials.create(props.accessKey(), props.secretKey()))
                : DefaultCredentialsProvider.builder().build();
    }

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        s3.putObject(
                b -> {
                    b.bucket(bucket).key(key).contentType(contentType).contentLength((long) bytes.length);
                    if (kmsKeyId != null) {
                        b.serverSideEncryption(ServerSideEncryption.AWS_KMS).ssekmsKeyId(kmsKeyId);
                    }
                },
                RequestBody.fromBytes(bytes));
        return new ObjectInfo(key, contentType, bytes.length);
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        try {
            var object = s3.getObjectAsBytes(b -> b.bucket(bucket).key(key));
            var bytes = object.asByteArray();
            return Optional.of(
                    new ObjectContent(new ObjectInfo(key, object.response().contentType(), bytes.length), bytes));
        } catch (NoSuchKeyException _) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        try {
            var head = s3.headObject(b -> b.bucket(bucket).key(key));
            return Optional.of(new ObjectInfo(key, head.contentType(), head.contentLength()));
        } catch (S3Exception ex) {
            if (ex.statusCode() == 404) {
                return Optional.empty();
            }
            throw ex;
        }
    }

    @Override
    public void delete(String key) {
        s3.deleteObject(b -> b.bucket(bucket).key(key));
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        var request = presigner.presignGetObject(b ->
                b.signatureDuration(ttl).getObjectRequest(g -> g.bucket(bucket).key(key)));
        try {
            return request.url().toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    @Override
    public void close() {
        presigner.close();
        s3.close();
    }
}
