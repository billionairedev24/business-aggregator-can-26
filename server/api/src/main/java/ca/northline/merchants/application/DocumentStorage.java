package ca.northline.merchants.application;

/**
 * Outbound port: where uploaded bytes live (S3 ca-central-1 in production; local disk under the {@code local} and
 * {@code test} profiles). Keys are opaque to callers.
 */
public interface DocumentStorage {
    /** @return the storage key */
    String put(String merchantId, String documentId, String contentType, byte[] bytes);

    byte[] get(String storageKey);
}
