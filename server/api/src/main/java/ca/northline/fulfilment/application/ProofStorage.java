package ca.northline.fulfilment.application;

import ca.northline.shared.Bytes;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;

/**
 * Outbound port: proof-of-delivery photos and signatures (S-86) — object storage in the cloud ({@code
 * STORAGE_PROVIDER}), a local folder under {@code local}/{@code test}. Keys are {@code <runId>/<stopId>-<kind>}.
 */
public interface ProofStorage {

    record StoredFile(Bytes bytes, String contentType) {}

    void put(String key, byte[] bytes, String contentType);

    Optional<StoredFile> get(String key);

    /** Removes one file; nothing happens when it is already gone (S-107 retention). */
    void delete(String key);

    /**
     * A URL anyone holding it can GET the file with until {@code ttl} passes: object storage's presigned GET (S-10), or
     * under {@code local}/{@code test} the api's own signed link. Hand it out only after the caller's own check (the
     * customer's proof-of-delivery photo: the order must be theirs).
     */
    URI signedUrl(String key, Duration ttl);
}
