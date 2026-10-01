package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.MediaStorage;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Listing images in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code catalogue/}. */
@Component
@UsesObjectStorage
class ObjectStoreMediaStorage implements MediaStorage {

    private final ObjectStore objects;

    ObjectStoreMediaStorage(ObjectStore store) {
        this.objects = store.within("catalogue");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes, contentType);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return objects.get(key).map(c -> c.bytes().toArray());
    }
}
