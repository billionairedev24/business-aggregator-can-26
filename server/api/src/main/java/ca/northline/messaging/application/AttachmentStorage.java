package ca.northline.messaging.application;

import java.util.Optional;

/** Outbound port: object storage for attachment bytes (S3 in production, a local fake under {@code local}/{@code test}). */
public interface AttachmentStorage {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);

    /** Removes everything under {@code <prefix>/} (a customer's uploads on erasure, S-105); returns how many. */
    int deleteAll(String prefix);

    /** Removes one object; nothing happens when it is already gone (S-107 retention). */
    void delete(String key);
}
