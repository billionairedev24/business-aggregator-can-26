package ca.northline.catalogue.domain;

import org.jspecify.annotations.Nullable;

/**
 * An uploaded image ({@code catalogue.media}). {@code storageKey} addresses the bytes in the media store;
 * {@code phash} is a 64-bit perceptual (average) hash used for the duplicate-image check.
 */
public record MediaAsset(
        String id,
        @Nullable String merchantId,
        String storageKey,
        String contentType,
        int width,
        int height,
        long byteSize,
        boolean onWhite,
        @Nullable Long phash) {

    /** Main-image standard: at least 1000 px on the longest side. */
    public boolean largeEnough() {
        return Math.max(width, height) >= ListingMessages.IMAGE_MIN_PX;
    }
}
