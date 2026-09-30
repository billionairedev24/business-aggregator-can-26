package ca.northline.catalogue.application;

import java.net.URI;
import java.util.Optional;

/**
 * Outbound port (S-35): downloads a product image a connected platform points to. The real adapter fetches HTTPS URLs
 * on the platforms' image hosts only (no redirects, size-capped); the local fake draws a placeholder.
 */
public interface ImageFetcher {

    /** The image bytes; empty when the URL isn't allowed, can't be fetched or is too large. */
    Optional<byte[]> fetch(URI url);
}
