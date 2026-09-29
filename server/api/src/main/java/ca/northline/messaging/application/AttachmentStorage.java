package ca.northline.messaging.application;

import java.util.Optional;

/** Outbound port: object storage for attachment bytes (S3 in production, a local fake under {@code local}/{@code test}). */
public interface AttachmentStorage {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);
}
