package ca.northline.booking.persistence;

import ca.northline.booking.application.MediaStore;
import ca.northline.shared.Conflict;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@link MediaStore} adapters. Object storage (S3, ca-central-1) is not wired yet; see docs/DECISIONS.md. */
final class MediaStores {
    private MediaStores() {}

    /** Local development and tests: keeps the bytes in memory. */
    @Component
    @Profile({"local", "test"})
    static class InMemoryMediaStore implements MediaStore {
        private final Map<String, byte[]> objects = new ConcurrentHashMap<>();

        @Override
        public String put(String merchantId, String mediaId, String contentType, byte[] bytes) {
            var key = "mem://booking/%s/%s".formatted(merchantId, mediaId);
            objects.put(key, bytes.clone());
            return key;
        }
    }

    /** Other profiles until the S3 adapter lands: uploads are refused with 409 {@code storage_unavailable}. */
    @Component
    @Profile("!local & !test")
    static class UnavailableMediaStore implements MediaStore {
        @Override
        public String put(String merchantId, String mediaId, String contentType, byte[] bytes) {
            throw new Conflict("storage_unavailable", "File uploads are not available yet.");
        }
    }
}
