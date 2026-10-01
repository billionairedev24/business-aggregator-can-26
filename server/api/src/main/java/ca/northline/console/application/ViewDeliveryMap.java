package ca.northline.console.application;

import ca.northline.region.api.DeliveryZones.Zone;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The delivery ops map's geometry (S-81, design 03 {@code delivery}): a market's centre and delivery zones (region
 * model), and the basemap tiles to draw under them when one is configured. Couriers' positions come from
 * {@code GET /api/v1/console/fulfilment/couriers?market=} (S-88: the latest only).
 */
public interface ViewDeliveryMap {

    /** @param marketId a region market id ({@code GET /api/v1/geo/regions}) */
    DeliveryMap map(String marketId);

    record Market(
            String id,
            String city,
            String province,
            @Nullable Double lat,
            @Nullable Double lng) {}

    /**
     * An XYZ raster tile service ({@code CONSOLE_MAP_TILES}): {@code {z}/{x}/{y}} in the URL template; none = the
     * console draws the zones on its own grid (the design's schematic map).
     */
    record Basemap(String tiles, String attribution) {}

    /** Outbound port: the basemap configuration ({@code northline.console.map.*}). */
    interface Basemaps {
        @Nullable
        Basemap current();
    }

    record DeliveryMap(
            Market market, List<Zone> zones, @Nullable Basemap basemap) {
        public DeliveryMap {
            zones = List.copyOf(zones);
        }
    }
}
