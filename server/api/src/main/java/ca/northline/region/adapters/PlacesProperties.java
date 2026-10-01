package ca.northline.region.adapters;

import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.places.*} (S-47, docs/runbooks/google-maps.md).
 *
 * @param provider {@code local} (fixture addresses; refused under staging/prod) | {@code google} — {@code PLACES_PROVIDER}
 * @param apiKey the server-side Google Maps Platform key ({@code GOOGLE_MAPS_API_KEY}); never sent to browsers
 * @param timeout connect and read timeout of each Google call (typeahead: fail fast)
 * @param perMinute address lookups per browsing session (or address) and minute, per api instance
 */
@ConfigurationProperties("northline.places")
record PlacesProperties(
        @DefaultValue("local") String provider,
        @Nullable String apiKey,
        @DefaultValue("https://places.googleapis.com") URI placesUrl,
        @DefaultValue("https://maps.googleapis.com") URI geocodingUrl,
        @DefaultValue("PT3S") Duration timeout,
        @DefaultValue("60") int perMinute) {

    String effectiveProvider() {
        return provider.isBlank() ? "local" : provider.strip().toLowerCase(Locale.ROOT);
    }

    String key() {
        return apiKey == null ? "" : apiKey.strip();
    }
}
