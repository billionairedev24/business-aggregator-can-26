package ca.northline.shared.integration;

import java.net.URI;
import java.util.Map;
import java.util.Optional;

/**
 * SPI: a module that starts OAuth consents on a platform completes the callback when the {@code state} is one it
 * stored. The shared callback endpoint asks each implementation in turn; the one that recognises the state answers
 * where the browser goes next (a Studio URL with the outcome).
 */
public interface OAuthCallback {

    /**
     * @param platform the path segment ({@code shopify}, {@code square}, {@code lightspeed}, {@code clover} …)
     * @param params every query parameter of the callback, as received
     * @return the Studio URL to send the browser to, or empty when the state isn't this module's (or the platform
     *     isn't one it handles)
     */
    Optional<URI> complete(String platform, Map<String, String> params);
}
