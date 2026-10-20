package ca.northline.booking.application;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-153): {@code booking.customer_photos} — the metadata of customers' photos. */
public interface CustomerPhotoStore {

    /**
     * @param refType {@code quote_request | booking}, null while not attached
     */
    record StoredPhoto(
            String id,
            String customerId,
            String storageKey,
            String contentType,
            long byteSize,
            String fileName,
            @Nullable String refType,
            @Nullable String refId,
            Instant createdAt) {}

    void insert(StoredPhoto photo);

    Optional<StoredPhoto> own(String customerId, String photoId);

    /** The customer's photos among {@code ids} that are not attached to a job yet. */
    List<StoredPhoto> attachable(String customerId, Collection<String> ids);

    void attach(String customerId, Collection<String> ids, String refType, String refId);

    /** The photos of a job, oldest first. */
    List<StoredPhoto> of(String refType, String refId);

    /**
     * The photo when the business may see it: attached to a quote request addressed to it, or to one of its bookings.
     */
    Optional<StoredPhoto> visibleTo(String merchantId, String photoId);
}
