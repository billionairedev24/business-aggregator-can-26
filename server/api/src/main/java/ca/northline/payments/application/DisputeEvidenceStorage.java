package ca.northline.payments.application;

import ca.northline.shared.Bytes;
import java.util.Optional;

/** Outbound port: dispute evidence files (object storage in production; a local folder under local/test). */
public interface DisputeEvidenceStorage {

    record StoredFile(Bytes bytes, String contentType) {}

    void put(String key, byte[] bytes, String contentType);

    Optional<StoredFile> get(String key);
}
