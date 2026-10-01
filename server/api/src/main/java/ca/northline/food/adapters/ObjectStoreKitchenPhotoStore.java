package ca.northline.food.adapters;

import ca.northline.food.application.KitchenPhotoStore;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/**
 * Menu-item photos in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code food/}. The dev-seed keys
 * ({@code seed/…}) have no object here, so seeded items show the placeholder tile.
 */
@Component
@UsesObjectStorage
class ObjectStoreKitchenPhotoStore implements KitchenPhotoStore {

    private final ObjectStore objects;

    ObjectStoreKitchenPhotoStore(ObjectStore store) {
        this.objects = store.within("food");
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
