package ca.northline.shared.storage;

import static ca.northline.support.ObjectStorageContainers.S3_ACCESS_KEY;
import static ca.northline.support.ObjectStorageContainers.S3_SECRET_KEY;

import ca.northline.platform.StorageProperties;
import ca.northline.support.ObjectStorageContainers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * {@code STORAGE_PROVIDER=s3} against RustFS (the compose {@code storage} profile's server) with endpoint + path-style
 * addressing — the same client code AWS S3 gets, minus the endpoint.
 */
class S3ObjectStoreTest extends ObjectStoreContract {

    static final String BUCKET = "s10-contract";

    static S3ObjectStore s3;

    static StorageProperties properties(String bucket) {
        return new StorageProperties(
                StorageProperties.Provider.S3,
                bucket,
                "ca-central-1",
                ObjectStorageContainers.s3Endpoint(),
                S3_ACCESS_KEY,
                S3_SECRET_KEY,
                true,
                null);
    }

    @BeforeAll
    static void createBucket() {
        ObjectStorageContainers.createS3Bucket(BUCKET);
        s3 = S3ObjectStore.create(properties(BUCKET));
    }

    @AfterAll
    static void close() {
        s3.close();
    }

    @Override
    ObjectStore provider() {
        return s3;
    }
}
