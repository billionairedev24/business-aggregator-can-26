package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import com.azure.core.http.jdk.httpclient.JdkHttpClientBuilder;
import com.azure.core.util.BinaryData;
import com.azure.core.util.Context;
import com.azure.identity.DefaultAzureCredentialBuilder;
import com.azure.storage.blob.BlobContainerClient;
import com.azure.storage.blob.BlobServiceClient;
import com.azure.storage.blob.BlobServiceClientBuilder;
import com.azure.storage.blob.models.BlobHttpHeaders;
import com.azure.storage.blob.models.BlobStorageException;
import com.azure.storage.blob.options.BlobParallelUploadOptions;
import com.azure.storage.blob.sas.BlobSasPermission;
import com.azure.storage.blob.sas.BlobServiceSasSignatureValues;
import com.azure.storage.common.StorageSharedKeyCredential;
import java.net.URI;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;

/**
 * Azure Blob Storage: {@code STORAGE_BUCKET} is the container, {@code STORAGE_ENDPOINT} the account URL. Credentials:
 * a shared key when {@code STORAGE_ACCESS_KEY} (account name) and {@code STORAGE_SECRET_KEY} (account key) are set
 * (Azurite), otherwise {@code DefaultAzureCredential} (AKS Workload Identity). {@code STORAGE_ENCRYPTION_KEY} = an
 * encryption scope (customer-managed key); otherwise the account's encryption (Microsoft-managed unless the account
 * sets a CMK). Presigned URLs are service SAS (shared key) or user-delegation SAS (Entra ID; the identity needs
 * "Storage Blob Delegator" or a role that includes {@code generateUserDelegationKey}). Uses the JDK HTTP client.
 */
@Slf4j
final class AzureBlobObjectStore implements ObjectStore {

    private static final Duration CLOCK_SKEW = Duration.ofMinutes(5);

    private final BlobServiceClient service;
    private final BlobContainerClient container;
    private final boolean sharedKey;
    private final Clock clock;

    AzureBlobObjectStore(BlobServiceClient service, String container, boolean sharedKey, Clock clock) {
        this.service = service;
        this.container = service.getBlobContainerClient(container);
        this.sharedKey = sharedKey;
        this.clock = clock;
    }

    static AzureBlobObjectStore create(StorageProperties props, Clock clock) {
        var endpoint = props.endpoint();
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalStateException(
                    "STORAGE_ENDPOINT (the account URL, https://<account>.blob.core.windows.net) is required for"
                            + " STORAGE_PROVIDER=azure");
        }
        var builder = new BlobServiceClientBuilder().endpoint(endpoint).httpClient(new JdkHttpClientBuilder().build());
        var sharedKey = StringUtils.hasText(props.accessKey()) && StringUtils.hasText(props.secretKey());
        if (sharedKey) {
            builder.credential(new StorageSharedKeyCredential(props.accessKey(), props.secretKey()));
        } else {
            builder.credential(new DefaultAzureCredentialBuilder().build());
        }
        if (StringUtils.hasText(props.encryptionKey())) {
            builder.encryptionScope(props.encryptionKey());
        }
        var bucket = StorageSettings.bucket(props);
        log.info(
                "Object storage: azure container={} endpoint={} auth={} encryptionScope={}",
                bucket,
                endpoint,
                sharedKey ? "shared-key" : "entra-id",
                StringUtils.hasText(props.encryptionKey()));
        return new AzureBlobObjectStore(builder.buildClient(), bucket, sharedKey, clock);
    }

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        var options = new BlobParallelUploadOptions(BinaryData.fromBytes(bytes))
                .setHeaders(new BlobHttpHeaders().setContentType(contentType));
        container.getBlobClient(key).uploadWithResponse(options, null, Context.NONE);
        return new ObjectInfo(key, contentType, bytes.length);
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        try {
            var response = container.getBlobClient(key).downloadContentWithResponse(null, null, null, Context.NONE);
            var bytes = response.getValue().toBytes();
            var type = response.getDeserializedHeaders().getContentType();
            return Optional.of(new ObjectContent(new ObjectInfo(key, type, bytes.length), bytes));
        } catch (BlobStorageException ex) {
            return notFound(ex);
        }
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        try {
            var properties = container.getBlobClient(key).getProperties();
            return Optional.of(new ObjectInfo(key, properties.getContentType(), properties.getBlobSize()));
        } catch (BlobStorageException ex) {
            return notFound(ex);
        }
    }

    @Override
    public void delete(String key) {
        container.getBlobClient(key).deleteIfExists();
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        var now = OffsetDateTime.now(clock).withOffsetSameInstant(ZoneOffset.UTC);
        var expiry = now.plus(ttl);
        var values = new BlobServiceSasSignatureValues(expiry, new BlobSasPermission().setReadPermission(true))
                .setStartTime(now.minus(CLOCK_SKEW));
        var blob = container.getBlobClient(key);
        var sas = sharedKey
                ? blob.generateSas(values)
                : blob.generateUserDelegationSas(values, service.getUserDelegationKey(now.minus(CLOCK_SKEW), expiry));
        return URI.create(blob.getBlobUrl() + "?" + sas);
    }

    private static <T> Optional<T> notFound(BlobStorageException ex) {
        if (ex.getStatusCode() == 404) {
            return Optional.empty();
        }
        throw ex;
    }
}
