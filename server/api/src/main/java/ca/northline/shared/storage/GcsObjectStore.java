package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import com.google.auth.ServiceAccountSigner;
import com.google.cloud.NoCredentials;
import com.google.cloud.storage.BlobId;
import com.google.cloud.storage.BlobInfo;
import com.google.cloud.storage.HttpMethod;
import com.google.cloud.storage.Storage;
import com.google.cloud.storage.Storage.BlobTargetOption;
import com.google.cloud.storage.Storage.SignUrlOption;
import com.google.cloud.storage.StorageOptions;
import java.net.URI;
import java.net.URISyntaxException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.util.StringUtils;

/**
 * Google Cloud Storage (JSON API over HTTP). Credentials: Application Default Credentials (GKE Workload Identity);
 * {@code STORAGE_ENDPOINT} points at an emulator (fake-gcs-server) and then no credentials are sent. {@code
 * STORAGE_ENCRYPTION_KEY} = a Cloud KMS key name (CMEK) per object; otherwise the bucket's default key (Google-managed
 * unless the bucket sets one). Presigned URLs are V4 signed URLs: on GKE the SDK signs through the IAM {@code signBlob}
 * API, so the service account needs {@code iam.serviceAccounts.signBlob} on itself.
 */
@Slf4j
final class GcsObjectStore implements ObjectStore, AutoCloseable {

    private static final String DEFAULT_TYPE = "application/octet-stream";

    private final Storage storage;
    private final String bucket;
    private final @Nullable String kmsKeyName;
    private final @Nullable String host;
    private final @Nullable ServiceAccountSigner signer;

    GcsObjectStore(
            Storage storage,
            String bucket,
            @Nullable String kmsKeyName,
            @Nullable String host,
            @Nullable ServiceAccountSigner signer) {
        this.storage = storage;
        this.bucket = bucket;
        this.kmsKeyName = StringUtils.hasText(kmsKeyName) ? kmsKeyName : null;
        this.host = StringUtils.hasText(host) ? host : null;
        this.signer = signer;
    }

    /** @param signer signs presigned URLs; {@code null} = the credentials the client runs with */
    static GcsObjectStore create(StorageProperties props, @Nullable ServiceAccountSigner signer) {
        var options = StorageOptions.http();
        var endpoint = props.endpoint();
        if (StringUtils.hasText(endpoint)) {
            options.setHost(endpoint)
                    .setCredentials(NoCredentials.getInstance())
                    .setProjectId("northline-emulator");
        }
        var bucket = StorageSettings.bucket(props);
        log.info(
                "Object storage: gcs bucket={} endpoint={} cmek={}",
                bucket,
                StringUtils.hasText(endpoint) ? endpoint : "google",
                StringUtils.hasText(props.encryptionKey()));
        return new GcsObjectStore(options.build().getService(), bucket, props.encryptionKey(), endpoint, signer);
    }

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        var info = BlobInfo.newBuilder(bucket, key).setContentType(contentType).build();
        if (kmsKeyName != null) {
            storage.create(info, bytes, BlobTargetOption.kmsKeyName(kmsKeyName));
        } else {
            storage.create(info, bytes);
        }
        return new ObjectInfo(key, contentType, bytes.length);
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        var blob = storage.get(BlobId.of(bucket, key));
        if (blob == null) {
            return Optional.empty();
        }
        var bytes = blob.getContent();
        return Optional.of(new ObjectContent(new ObjectInfo(key, type(blob.getContentType()), bytes.length), bytes));
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        return Optional.ofNullable(storage.get(BlobId.of(bucket, key)))
                .map(b -> new ObjectInfo(key, type(b.getContentType()), Objects.requireNonNullElse(b.getSize(), 0L)));
    }

    @Override
    public void delete(String key) {
        storage.delete(BlobId.of(bucket, key));
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        var options = new ArrayList<SignUrlOption>();
        options.add(SignUrlOption.withV4Signature());
        options.add(SignUrlOption.httpMethod(HttpMethod.GET));
        if (host != null) {
            options.add(SignUrlOption.withHostName(host));
            options.add(SignUrlOption.withPathStyle());
        }
        if (signer != null) {
            options.add(SignUrlOption.signWith(signer));
        }
        var url = storage.signUrl(
                BlobInfo.newBuilder(bucket, key).build(),
                ttl.toSeconds(),
                TimeUnit.SECONDS,
                options.toArray(SignUrlOption[]::new));
        try {
            return url.toURI();
        } catch (URISyntaxException ex) {
            throw new IllegalStateException(ex);
        }
    }

    private static String type(@Nullable String contentType) {
        return contentType == null ? DEFAULT_TYPE : contentType;
    }

    @Override
    public void close() throws Exception {
        storage.close();
    }
}
