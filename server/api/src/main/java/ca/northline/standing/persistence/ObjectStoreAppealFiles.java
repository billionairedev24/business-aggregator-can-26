package ca.northline.standing.persistence;

import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.storage.ObjectStore;
import ca.northline.standing.application.AppealFiles;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link AppealFiles} on the shared {@link ObjectStore} under {@code standing/} (a folder under {@code local}/{@code
 * test}, S3 / GCS / Azure Blob in the cloud), with the key rules and the {@code VirusScanner} hook. Without an object
 * store ({@code dev} with {@code STORAGE_PROVIDER=local}) uploads answer 409 {@code storage_unavailable}.
 */
@Component
class ObjectStoreAppealFiles implements AppealFiles {

    private final @Nullable ObjectStore objects;

    ObjectStoreAppealFiles(ObjectProvider<ObjectStore> store) {
        var shared = store.getIfAvailable();
        this.objects = shared == null ? null : shared.within("standing");
    }

    @Override
    public void put(String key, Bytes bytes, String contentType) {
        store().put(key, bytes.toArray(), contentType);
    }

    @Override
    public Optional<Bytes> get(String key) {
        return store().get(key).map(ObjectStore.ObjectContent::bytes);
    }

    private ObjectStore store() {
        if (objects == null) {
            throw new Conflict("storage_unavailable", "File uploads are not available yet.");
        }
        return objects;
    }
}
