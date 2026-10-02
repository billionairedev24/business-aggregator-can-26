package ca.northline.messaging.adapters;

import ca.northline.messaging.application.AttachmentStorage;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Message and case attachments in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code messaging/}. */
@Component
@UsesObjectStorage
class ObjectStoreAttachmentStorage implements AttachmentStorage {

    private final ObjectStore objects;

    ObjectStoreAttachmentStorage(ObjectStore store) {
        this.objects = store.within("messaging");
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
