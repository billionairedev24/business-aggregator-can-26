package ca.northline.booking.application;

import ca.northline.shared.Bytes;
import java.util.Optional;

/**
 * Outbound port (S-153): the bytes of customers' photos in object storage, keys {@code customers/<userId>/<id>.<ext>}
 * under {@code booking/}. Every write passes the platform's virus-scanner hook (a 422 on {@code file} when it refuses).
 */
public interface CustomerPhotoStorage {

    void put(String key, Bytes bytes, String contentType);

    Optional<Bytes> get(String key);
}
