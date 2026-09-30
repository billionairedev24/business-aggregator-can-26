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

    /**
     * A customer's location: coordinates when the device gave them, and the city of the location pill (the caller's
     * default market when the customer has none — never a city chosen here).
     */
    record Place(@Nullable Double lat, @Nullable Double lng, String city) {

        public boolean hasPoint() {
            return lat != null && lng != null;
        }
    }

    /** The zone names of each business, alphabetical; businesses without zones are absent. */
    Map<String, List<String>> zones(Collection<String> merchantIds);

    /** Those of {@code merchantIds} whose service area covers {@code place}. */
    Set<String> covering(Collection<String> merchantIds, Place place);

    /** The zone the point lies in ("Beltline"), the one whose centre is nearest when zones overlap; empty without a point or outside. */
    Optional<String> zoneAt(Place place);
}
