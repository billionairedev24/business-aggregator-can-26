package ca.northline.region.api;

import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A market's delivery zones ({@code region.zones}, PostGIS polygons): their outline for the console's ops map and their
 * pricing for the zone economics (S-81). Region data — the zones a market has are rows, never code.
 */
public interface DeliveryZones {

    /** The zones of a market ({@code region.regions} id of kind {@code market}), by sort then name. */
    List<Zone> inMarket(String marketId);

    /**
     * @param ring the polygon's outer ring, first point repeated last; empty when the zone has no boundary yet
     * @param runsPerDay pooled runs a day, null when not set
     */
    record Zone(
            String id,
            String marketId,
            String name,
            List<Point> ring,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents) {

        public Zone {
            ring = List.copyOf(ring);
        }
    }

    record Point(double lat, double lng) {}
}
