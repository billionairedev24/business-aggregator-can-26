package ca.northline.booking.persistence;

import ca.northline.booking.application.MediaStore;
import ca.northline.shared.Conflict;
import ca.northline.shared.storage.ObjectKeys;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesLocalStorage;
import ca.northline.shared.storage.UsesObjectStorage;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@link MediaStore} adapters, picked by {@code northline.storage.provider} (S-10). */
final class MediaStores {
    private MediaStores() {}

    /** Local development and tests: keeps the bytes in memory. */
    @Component
    @Profile({"local", "test"})
    @UsesLocalStorage
    static class InMemoryMediaStore implements MediaStore {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String put(String merchantId, String mediaId, String contentType, byte[] bytes) {
            var key = "mem://booking/%s/%s".formatted(merchantId, mediaId);
            objects.put(key, bytes.clone());
            return key;
        }
    }

    /** Other profiles while {@code STORAGE_PROVIDER=local}: uploads are refused with 409 {@code storage_unavailable}. */
    @Component
    @Profile("!local & !test")
    @UsesLocalStorage
    static class UnavailableMediaStore implements MediaStore {
        @Override
        public String put(String merchantId, String mediaId, String contentType, byte[] bytes) {
            throw new Conflict("storage_unavailable", "File uploads are not available yet.");
        }
    }

    /** {@code STORAGE_PROVIDER=s3|gcs|azure}: {@code booking/<merchantId>/<mediaId>.<ext>}, key relative to {@code booking/}. */
    @Component
    @UsesObjectStorage
    static class ObjectStoreMediaStore implements MediaStore {
        private final ObjectStore objects;

        ObjectStoreMediaStore(ObjectStore store) {
            this.objects = store.within("booking");
        }

        @Override
        public String put(String merchantId, String mediaId, String contentType, byte[] bytes) {
            return objects.put(ObjectKeys.merchantObject(merchantId, mediaId, contentType), bytes, contentType)
                    .key();
        }
    }
}
