package ca.northline.shared.storage;

import ca.northline.platform.StorageProperties;
import ca.northline.support.ObjectStorageContainers;
import java.time.Clock;
import org.junit.jupiter.api.BeforeAll;

/** {@code STORAGE_PROVIDER=azure} against Azurite with its development account's shared key (service SAS). */
class AzureBlobObjectStoreTest extends ObjectStoreContract {

    static final String CONTAINER = "s10-contract";

    static AzureBlobObjectStore azure;

    @BeforeAll
    static void createContainer() {
        ObjectStorageContainers.createAzureContainer(CONTAINER);
        azure = AzureBlobObjectStore.create(
                new StorageProperties(
                        StorageProperties.Provider.AZURE,
                        CONTAINER,
                        null,
                        ObjectStorageContainers.azureEndpoint(),
                        ObjectStorageContainers.AZURITE_ACCOUNT,
                        ObjectStorageContainers.AZURITE_KEY,
                        false,
                        null),
                Clock.systemUTC());
    }

    @Override
    ObjectStore provider() {
        return azure;
    }
}
