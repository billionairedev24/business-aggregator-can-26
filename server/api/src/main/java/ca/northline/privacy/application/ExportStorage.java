package ca.northline.privacy.application;

import java.util.Optional;

/**
 * Outbound port: the sealed export bundles, in object storage under {@code privacy/} ({@code STORAGE_PROVIDER}; a
 * folder under {@code local}/{@code test}). The bytes are already encrypted by the time they get here.
 */
public interface ExportStorage {

    void put(String key, byte[] bytes);

    Optional<byte[]> get(String key);

    void delete(String key);
}
