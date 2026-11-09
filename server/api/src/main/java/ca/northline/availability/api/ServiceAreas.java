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
     * A customer's location: coordinates when the device gave them, else the city of the location pill; neither = the
     * place isn't known (S-190: the providers list then shows every provider; {@link #covering} answers nobody).
     */
    record Place(
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable String city) {

        public boolean hasPoint() {
            return lat != null && lng != null;
        }
    }

    /** The zone names of each business, alphabetical; businesses without zones are absent. */
    Map<String, List<String>> zones(Collection<String> merchantIds);

    /** Those of {@code merchantIds} whose service area covers {@code place}. */
    Set<String> covering(Collection<String> merchantIds, Place place);

    /**
     * S-190: km from the point to the nearest centre of each business's zones (one decimal); businesses without zones,
     * or a place without a point, are absent.
     */
    Map<String, Double> distanceKm(Collection<String> merchantIds, Place place);

    /** The zone the point lies in (a neighbourhood), the one whose centre is nearest when zones overlap; empty without a point or outside. */
    Optional<String> zoneAt(Place place);
}
