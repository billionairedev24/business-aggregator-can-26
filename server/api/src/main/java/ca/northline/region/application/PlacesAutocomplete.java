package ca.northline.region.application;

import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.PlaceParts;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port (S-47): address suggestions, their details and reverse geocoding, restricted to Canada. Chosen by
 * {@code northline.places.provider} ({@code PLACES_PROVIDER}): {@code local} (a fixture list of Canadian addresses) or
 * {@code google} (Places API (New) + Geocoding API with a server-side key, {@code GOOGLE_MAPS_API_KEY}).
 *
 * <p>{@code sessionToken} groups one person's keystrokes and the final details call into one billed session (Google's
 * session tokens); the browser makes one per address search.
 */
public interface PlacesAutocomplete {

    /** "powered by Google" or the fake's name, for the attribution under the suggestions. */
    String attribution();

    List<Prediction> autocomplete(String input, @Nullable String sessionToken, Locale locale, @Nullable GeoPoint near);

    Optional<PlaceParts> details(String placeId, @Nullable String sessionToken, Locale locale);

    /** The street address (or, failing that, the neighbourhood / city) at a point; empty when nothing is there. */
    Optional<PlaceParts> reverse(GeoPoint point, Locale locale);

    /** One suggestion: {@code main} = the street address, {@code secondary} = "{city}, {province} {postal code}, Canada". */
    record Prediction(String placeId, String main, String secondary) {}

    /** The provider can't answer now (network, quota, key refused). The screen asks to try again. */
    final class Unavailable extends RuntimeException {
        public Unavailable(String message, @Nullable Throwable cause) {
            super(message, cause);
        }
    }
}
