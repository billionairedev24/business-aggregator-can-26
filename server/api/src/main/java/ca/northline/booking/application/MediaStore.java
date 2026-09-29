package ca.northline.booking.application;

/**
 * Outbound port to object storage (S3 in production) for quote attachments and completion photos. The {@code local}
 * and {@code test} profiles use an in-memory fake.
 */
public interface MediaStore {

    /** Stores the bytes and returns the storage key. */
    String put(String merchantId, String mediaId, String contentType, byte[] bytes);
}
