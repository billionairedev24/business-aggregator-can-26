package ca.northline.fulfilment.application;

import ca.northline.shared.Bytes;
import java.util.Optional;

/**
 * Outbound port: proof-of-delivery photos and signatures (S-86) — object storage in the cloud ({@code
 * STORAGE_PROVIDER}), a local folder under {@code local}/{@code test}. Keys are {@code <runId>/<stopId>-<kind>}.
 */
public interface ProofStorage {

    record StoredFile(Bytes bytes, String contentType) {}

    void put(String key, byte[] bytes, String contentType);

    Optional<StoredFile> get(String key);
}
