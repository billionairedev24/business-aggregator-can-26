package ca.northline.uat.application;

import java.util.Optional;

/**
 * Outbound port: screenshots in object storage under {@code uat/} ({@code STORAGE_PROVIDER}), keyed
 * {@code customers/<userId>/<id>.<ext>} so a person's erasure removes them with one prefix.
 */
public interface ScreenshotStorage {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);

    void delete(String key);

    int deleteAll(String prefix);
}
