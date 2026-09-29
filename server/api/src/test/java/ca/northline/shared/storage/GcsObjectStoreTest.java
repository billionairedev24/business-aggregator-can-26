package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import ca.northline.support.ObjectStorageContainers;
import com.google.auth.oauth2.ServiceAccountCredentials;
import java.security.KeyPairGenerator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;

/**
 * {@code STORAGE_PROVIDER=gcs} against fake-gcs-server. The emulator takes no credentials, so presigned URLs are signed
 * with a throw-away service-account key (in GKE the SDK signs through IAM {@code signBlob}); the emulator serves them
 * without checking the signature.
 */
class GcsObjectStoreTest extends ObjectStoreContract {

    static final String BUCKET = "s10-contract";

    static GcsObjectStore gcs;

    @BeforeAll
    static void createBucket() throws Exception {
        ObjectStorageContainers.createGcsBucket(BUCKET);
        var keys = KeyPairGenerator.getInstance("RSA");
        keys.initialize(2048);
        var signer = ServiceAccountCredentials.newBuilder()
                .setClientEmail("northline-api@northline-emulator.iam.gserviceaccount.com")
                .setPrivateKey(keys.generateKeyPair().getPrivate())
                .setProjectId("northline-emulator")
                .build();
        gcs = GcsObjectStore.create(
                new StorageProperties(
                        StorageProperties.Provider.GCS,
                        BUCKET,
                        null,
                        ObjectStorageContainers.gcsEndpoint(),
                        null,
                        null,
                        false,
                        null),
                signer);
    }

    @AfterAll
    static void close() throws Exception {
        gcs.close();
    }

    @Override
    ObjectStore provider() {
        return gcs;
    }
}
