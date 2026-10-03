package ca.northline.uat.adapters;

import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import ca.northline.uat.application.ScreenshotStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** UAT feedback screenshots in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code uat/}. */
@Component
@UsesObjectStorage
class ObjectStoreScreenshotStorage implements ScreenshotStorage {

    private final ObjectStore objects;

    ObjectStoreScreenshotStorage(ObjectStore store) {
        this.objects = store.within("uat");
    }

    @Override
    public void put(String key, byte[] bytes, String contentType) {
        objects.put(key, bytes, contentType);
    }

    @Override
    public Optional<byte[]> get(String key) {
        return objects.get(key).map(c -> c.bytes().toArray());
    }

    @Override
    public void delete(String key) {
        objects.delete(key);
    }

    @Override
    public int deleteAll(String prefix) {
        return objects.deleteAll(prefix);
    }
}
