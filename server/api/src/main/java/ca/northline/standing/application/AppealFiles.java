package ca.northline.standing.application;

import ca.northline.shared.Bytes;
import java.util.Optional;

/**
 * Outbound port: appeal evidence in object storage, keys {@code <merchantId>/<id>.<ext>} under {@code standing/}. Every
 * write passes the platform's virus-scanner hook. 409 {@code storage_unavailable} where no store exists.
 */
public interface AppealFiles {

    void put(String key, Bytes bytes, String contentType);

    Optional<Bytes> get(String key);
}
