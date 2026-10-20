package ca.northline.orders.application;

import ca.northline.shared.Bytes;
import java.util.Optional;

/** Outbound port (S-151): customers' photos of returned items, in object storage under {@code orders/returns/}. */
public interface ReturnPhotos {

    void put(String key, Bytes bytes, String contentType);

    Optional<Stored> get(String key);

    record Stored(Bytes bytes, String contentType) {}
}
