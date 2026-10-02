package ca.northline.privacy.adapters;

import ca.northline.privacy.application.ExportStorage;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Export bundles in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code privacy/exports/}. */
@Component
@UsesObjectStorage
class ObjectStoreExportStorage implements ExportStorage {

    static final String TYPE = "application/octet-stream";

    private final ObjectStore objects;

    ObjectStoreExportStorage(ObjectStore store) {
        this.objects = store.within("privacy");
    }

    @Override
    public void put(String key, byte[] bytes) {
        objects.put(key, bytes, TYPE);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return objects.get(key).map(c -> c.bytes().toArray());
    }

    @Override
    public void delete(String key) {
        objects.delete(key);
    }
}
