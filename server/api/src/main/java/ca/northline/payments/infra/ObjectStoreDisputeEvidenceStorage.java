package ca.northline.payments.infra;

import ca.northline.payments.application.DisputeEvidenceStorage;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Dispute evidence in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code payments/}; the content type
 * is the object's own.
 */
@Component
@UsesObjectStorage
class ObjectStoreDisputeEvidenceStorage implements DisputeEvidenceStorage {

    private final ObjectStore objects;

    ObjectStoreDisputeEvidenceStorage(ObjectStore store) {
        this.objects = store.within("payments");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes, contentType);
    }

    @Override
    public Optional<StoredFile> get(String key) {
        return objects.get(key).map(c -> new StoredFile(c.bytes(), c.info().contentType()));
    }

    @Override
    public void delete(String key) {
        objects.delete(key);
    }
}
