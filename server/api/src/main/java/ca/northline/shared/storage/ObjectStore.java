package ca.northline.shared.storage;

import ca.northline.shared.Bytes;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * A bucket (S3, GCS) or container (Azure) of objects addressed by keys such as {@code merchants/01J…/01J….pdf}. Keys are
 * relative paths of {@code [A-Za-z0-9._-]} segments ({@link ObjectKeys#requireValid}); a missing object is an empty
 * {@link Optional}, never an exception. Implementations are thread-safe. Bytes are held in memory: uploads are capped
 * at a few MB by every caller.
 */
public interface ObjectStore {

    /** Longest lifetime of a presigned URL. */
    Duration MAX_PRESIGN_TTL = Duration.ofHours(1);

    /** Stores (or replaces) the object with its content type; encrypted at rest by the provider. */
    ObjectInfo put(String key, byte[] bytes, String contentType);

    Optional<ObjectContent> get(String key);

    /** Content type and size without the bytes. */
    Optional<ObjectInfo> info(String key);

    default boolean exists(String key) {
        return info(key).isPresent();
    }

    /** Removes the object; deleting a missing object is not an error. */
    void delete(String key);

    /**
     * Removes every object whose key starts with {@code <prefix>/} — a person's or a business's uploads, on erasure
     * (S-105). Idempotent; returns how many objects were removed.
     */
    int deleteAll(String prefix);

    /**
     * A URL anyone holding it can GET the object with until {@code ttl} (≤ {@link #MAX_PRESIGN_TTL}) passes. Hand it out
     * only after the caller's own authorization check — the URL itself carries no user.
     */
    URI presignGet(String key, Duration ttl);

    /** The same store with every key under {@code <prefix>/}; keys passed in and returned are relative to it. */
    default ObjectStore within(String prefix) {
        return new PrefixedObjectStore(this, ObjectKeys.requireValid(prefix));
    }

    /** What is stored: key, content type and size in bytes. */
    record ObjectInfo(String key, String contentType, long size) {}

    /** An object with its bytes. */
    record ObjectContent(ObjectInfo info, Bytes bytes) {}
}
