package ca.northline.platform;

import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.storage.*} — object storage for uploads (media, documents, attachments, evidence, photos). Only
 * {@code local} exists today (a folder on disk under the {@code local}/{@code test} profiles, "unconfigured" elsewhere);
 * the S3-compatible, Google Cloud Storage and Azure Blob adapters come with backlog story S-10 and are chosen by
 * {@link #provider()} alone, so switching cloud needs no code change.
 *
 * @param provider {@code local | s3 | gcs | azure} ({@code STORAGE_PROVIDER})
 * @param bucket bucket (S3, GCS) or container (Azure) ({@code STORAGE_BUCKET})
 * @param region e.g. {@code ca-central-1}, {@code northamerica-northeast1}, {@code canadacentral} ({@code
 *     STORAGE_REGION})
 * @param endpoint S3-compatible endpoint (MinIO/RustFS locally, e.g. {@code http://localhost:9100}) or the Azure
 *     account URL; empty for AWS S3 and GCS ({@code STORAGE_ENDPOINT})
 * @param accessKey static access key for S3-compatible servers; empty in the cloud, where workload identity is used
 *     ({@code STORAGE_ACCESS_KEY})
 * @param secretKey matching secret ({@code STORAGE_SECRET_KEY})
 * @param pathStyle path-style bucket addressing (needed by MinIO/RustFS) ({@code STORAGE_PATH_STYLE})
 */
@ConfigurationProperties("northline.storage")
public record StorageProperties(
        @DefaultValue("local") Provider provider,
        @Nullable String bucket,
        @Nullable String region,
        @Nullable String endpoint,
        @Nullable String accessKey,
        @Nullable String secretKey,
        @DefaultValue("false") boolean pathStyle) {

    /** Which adapter stores the bytes. */
    public enum Provider {
        LOCAL,
        S3,
        GCS,
        AZURE
    }
}
