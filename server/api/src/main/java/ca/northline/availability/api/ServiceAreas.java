package ca.northline.availability.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Where businesses work (S-53): the zones of Availability › Booking rules › Service area, and which businesses cover a
 * customer's location. A zone is a named area ({@code availability.service_zones}, V114); a business covers a place when
 * one of its zones contains the point, or — when only the city is known — when one of its zones is in that city.
 */
public interface ServiceAreas {

    /** A customer's location: coordinates when the device gave them, else the city of the location pill. */
    record Place(
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable String city) {

        /** No location at all: the site falls back to Calgary (docs/CONSUMER_WEB_PLAN.md § Location). */
        public static final String FALLBACK_CITY = "Calgary";

        public boolean hasPoint() {
            return lat != null && lng != null;
        }

        public String cityOrFallback() {
            return city == null || city.isBlank() ? FALLBACK_CITY : city.strip();
        }
    }

    /** The zone names of each business, alphabetical; businesses without zones are absent. */
    Map<String, List<String>> zones(Collection<String> merchantIds);

    /** Those of {@code merchantIds} whose service area covers {@code place}. */
    Set<String> covering(Collection<String> merchantIds, Place place);

    /** The zone the point lies in ("Beltline"), the one whose centre is nearest when zones overlap; empty without a point or outside. */
    Optional<String> zoneAt(Place place);
}
