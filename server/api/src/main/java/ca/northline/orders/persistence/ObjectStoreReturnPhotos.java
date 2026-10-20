package ca.northline.orders.persistence;

import ca.northline.orders.application.ReturnPhotos;
import ca.northline.shared.Bytes;
import ca.northline.shared.Conflict;
import ca.northline.shared.storage.ObjectStore;
import java.util.Optional;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Component;

/**
 * {@link ReturnPhotos} in the platform's object store ({@code STORAGE_PROVIDER}; a folder under {@code local} /
 * {@code test}) under {@code orders/returns/}. Where no store is configured (the {@code local} provider outside
 * {@code local}/{@code test}) an upload answers 409 {@code storage_unavailable} instead of losing the photo.
 */
@Component
class ObjectStoreReturnPhotos implements ReturnPhotos {

    private final ObjectProvider<ObjectStore> stores;

    ObjectStoreReturnPhotos(ObjectProvider<ObjectStore> stores) {
        this.stores = stores;
    }

    private ObjectStore store() {
        var store = stores.getIfAvailable();
        if (store == null) {
            throw new Conflict("storage_unavailable", "Photos can't be stored right now. Try again later.");
        }
        return store.within("orders").within("returns");
    }

    @Override
    public void put(String key, Bytes bytes, String contentType) {
        store().put(key, bytes.toArray(), contentType);
    }

    @Override
    public Optional<Stored> get(String key) {
        return store().get(key).map(c -> new Stored(c.bytes(), c.info().contentType()));
    }
}
