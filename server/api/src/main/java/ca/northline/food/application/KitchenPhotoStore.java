package ca.northline.food.application;

import java.util.Optional;

/** Outbound port: object storage for menu-item photos (S3 in production; a local fake under {@code local}/{@code test}). */
public interface KitchenPhotoStore {

    void put(String key, byte[] bytes, String contentType);

    Optional<byte[]> get(String key);
}
