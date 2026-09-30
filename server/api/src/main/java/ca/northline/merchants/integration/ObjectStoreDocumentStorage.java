package ca.northline.merchants.integration;

import ca.northline.merchants.application.DocumentStorage;
import ca.northline.shared.NotFound;
import ca.northline.shared.storage.ObjectKeys;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import org.springframework.stereotype.Component;

/**
 * Onboarding documents, verification evidence and logos in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}):
 * {@code merchants/<merchantId>/<documentId>.<ext>}. The stored key is relative to {@code merchants/}.
 */
@Component
@UsesObjectStorage
class ObjectStoreDocumentStorage implements DocumentStorage {

    private final ObjectStore objects;

    ObjectStoreDocumentStorage(ObjectStore store) {
        this.objects = store.within("merchants");
    }

    @Override
    public String put(String merchantId, String documentId, String contentType, byte[] bytes) {
        return objects.put(ObjectKeys.merchantObject(merchantId, documentId, contentType), bytes, contentType)
                .key();
    }

    @Override
    public byte[] get(String storageKey) {
        return objects.get(storageKey)
                .map(ObjectStore.ObjectContent::bytes)
                .orElseThrow(() -> new NotFound("file", storageKey));
    }
}
