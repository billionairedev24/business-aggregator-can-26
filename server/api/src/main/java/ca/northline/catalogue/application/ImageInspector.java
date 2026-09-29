package ca.northline.catalogue.application;

import java.util.Optional;

/** Outbound port: decodes an image and measures what the image standards need. */
public interface ImageInspector {

    /**
     * @param contentType {@code image/jpeg} or {@code image/png}
     * @param onWhite the border pixels are near-white (main image on pure white)
     * @param phash 64-bit average hash for the duplicate index
     */
    record ImageFacts(String contentType, int width, int height, boolean onWhite, long phash) {}

    /** Empty when the bytes are not a JPG or PNG. */
    Optional<ImageFacts> inspect(byte[] bytes);
}
