package ca.northline.fulfilment.infra;

import ca.northline.fulfilment.application.ProofStorage;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.shared.storage.UsesObjectStorage;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Proofs of delivery in object storage ({@code STORAGE_PROVIDER=s3|gcs|azure}) under {@code fulfilment/proofs/}. */
@Component
@UsesObjectStorage
class ObjectStoreProofStorage implements ProofStorage {

    private final ObjectStore objects;

    ObjectStoreProofStorage(ObjectStore store) {
        this.objects = store.within("fulfilment").within("proofs");
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

    @Override
    public URI signedUrl(String key, Duration ttl) {
        return objects.presignGet(key, ttl);
    }
}
