package ca.northline.booking.persistence;

import ca.northline.booking.application.CustomerPhotoStorage;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.storage.ObjectStore;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link CustomerPhotoStorage} on the shared {@link ObjectStore} under {@code booking/} — the S-10 bean for every
 * provider (a folder under {@code local}/{@code test}, S3 / GCS / Azure Blob in the cloud), always wrapped with the key
 * rules and the {@code VirusScanner} hook. Where no object store exists ({@code dev} with {@code STORAGE_PROVIDER=local})
 * uploads answer 409 {@code storage_unavailable} rather than keeping files on a pod's disk.
 */
@Component
class ObjectStoreCustomerPhotoStorage implements CustomerPhotoStorage {

    private final @Nullable ObjectStore objects;

    ObjectStoreCustomerPhotoStorage(ObjectProvider<ObjectStore> store) {
        var shared = store.getIfAvailable();
        this.objects = shared == null ? null : shared.within("booking");
    }

    @Override
    public void put(String key, Bytes bytes, String contentType) {
        store().put(key, bytes.toArray(), contentType);
    }

    @Override
    public Optional<Bytes> get(String key) {
        return store().get(key).map(ObjectStore.ObjectContent::bytes);
    }

    private ObjectStore store() {
        if (objects == null) {
            throw new Conflict("storage_unavailable", "File uploads are not available yet.");
        }
        return objects;
    }
}
