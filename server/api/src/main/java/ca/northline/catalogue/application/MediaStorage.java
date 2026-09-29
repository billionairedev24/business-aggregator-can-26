package ca.northline.catalogue.application;

import java.util.Optional;

/** Outbound port: object storage for image bytes (S3 in production, a local fake under {@code local}/{@code test}). */
public interface MediaStorage {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);
}
