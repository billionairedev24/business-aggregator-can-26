package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.storage.*} — object storage for uploads (media, documents, attachments, evidence, photos), read by
 * the api's {@code ca.northline.shared.storage} (S-10). {@code local} keeps each module's folder-on-disk fake under the
 * {@code local}/{@code test} profiles (fail-loudly placeholders under {@code dev}; refused under {@code staging}/{@code
 * prod}); {@code s3} (AWS S3 or any S3-compatible server), {@code gcs} and {@code azure} select the object-store
 * adapters. Switching cloud is a configuration change.
 *
 * @param provider {@code local | s3 | gcs | azure} ({@code STORAGE_PROVIDER})
 * @param bucket bucket (S3, GCS) or container (Azure) ({@code STORAGE_BUCKET})
 * @param region e.g. {@code ca-central-1}, {@code northamerica-northeast1}, {@code canadacentral} ({@code
 *     STORAGE_REGION})
 * @param endpoint S3-compatible endpoint (RustFS/MinIO locally, e.g. {@code http://localhost:9100}), the Azure account
 *     URL ({@code https://<account>.blob.core.windows.net}, required for {@code azure}), or a GCS emulator; empty for
 *     AWS S3 and Google Cloud Storage ({@code STORAGE_ENDPOINT})
 * @param accessKey static access key for S3-compatible servers, or the Azure storage account name when a shared key is
 *     used (Azurite); empty in the cloud, where workload identity is used ({@code STORAGE_ACCESS_KEY})
 * @param secretKey matching secret / Azure account key ({@code STORAGE_SECRET_KEY})
 * @param pathStyle path-style bucket addressing (needed by RustFS/MinIO) ({@code STORAGE_PATH_STYLE})
 * @param encryptionKey customer-managed key: AWS KMS key ARN/alias (SSE-KMS), Cloud KMS key name (CMEK) or Azure
 *     encryption scope; empty = the provider's default encryption at rest (SSE-S3, Google-managed, Microsoft-managed)
 *     ({@code STORAGE_ENCRYPTION_KEY})
 */
@ConfigurationProperties("northline.storage")
public record StorageProperties(
        @DefaultValue("local") Provider provider,
        @Nullable String bucket,
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String accessKey,
        @Nullable String secretKey,
        @DefaultValue("false") boolean pathStyle,
        @Nullable String encryptionKey) {

    /** Which adapter stores the bytes. */
    public enum Provider {
        LOCAL,
        S3,
        GCS,
        AZURE
    }
}
