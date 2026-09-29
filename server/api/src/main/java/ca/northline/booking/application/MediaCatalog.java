package ca.northline.booking.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

/** Outbound port for the metadata of uploaded files ({@code booking.media}). */
public interface MediaCatalog {

    void insert(MediaInfo media);

    /** The files among {@code ids} that belong to the merchant, in no particular order. */
    List<MediaInfo> find(String merchantId, Collection<String> ids);

    record MediaInfo(
            String id,
            String merchantId,
            String fileName,
            String contentType,
            long sizeBytes,
            String storageKey,
            String createdBy,
            Instant createdAt) {}
}
