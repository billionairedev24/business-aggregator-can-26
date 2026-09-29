package ca.northline.shared.storage;

import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/** {@link ObjectStore#within(String)}: prepends {@code <prefix>/} on the way in, strips it from returned keys. */
record PrefixedObjectStore(ObjectStore store, String prefix) implements ObjectStore {

    @Override
    public ObjectInfo put(String key, byte[] bytes, String contentType) {
        return relative(store.put(full(key), bytes, contentType));
    }

    @Override
    public Optional<ObjectContent> get(String key) {
        return store.get(full(key)).map(c -> new ObjectContent(relative(c.info()), c.bytes()));
    }

    @Override
    public Optional<ObjectInfo> info(String key) {
        return store.info(full(key)).map(this::relative);
    }

    @Override
    public void delete(String key) {
        store.delete(full(key));
    }

    @Override
    public URI presignGet(String key, Duration ttl) {
        return store.presignGet(full(key), ttl);
    }

    private String full(String key) {
        return prefix + "/" + ObjectKeys.requireValid(key);
    }

    private ObjectInfo relative(ObjectInfo info) {
        return new ObjectInfo(info.key().substring(prefix.length() + 1), info.contentType(), info.size());
    }
}
