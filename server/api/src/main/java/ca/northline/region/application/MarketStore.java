package ca.northline.region.application;

import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.Stage;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: provinces, markets and zones ({@code region.regions}, {@code region.zones}) and the waitlist. */
public interface MarketStore {

    /** Provinces and markets, provinces first, each by sort. */
    List<RegionRow> regions();

    /** The market whose radius covers the point, the nearest centre first. */
    Optional<RegionRow> marketAt(GeoPoint point);

    /** The nearest market of a province, however far. */
    Optional<RegionRow> nearestMarket(String province, GeoPoint point);

    Optional<RegionRow> province(String code);

    Optional<RegionRow> region(String id);

    /** The zone of the market that contains the point. */
    Optional<ZoneRow> zoneAt(String marketId, GeoPoint point);

    /** Adds the entry; false when the person (user id, else email) is already on that list. */
    boolean joinWaitlist(String id, String regionId, @Nullable String userId, @Nullable String email, String locale);

    /**
     * @param parentId the province of a market; null for a province
     * @param city a market's city; null for a province
     */
    record RegionRow(
            String id,
            String kind,
            @Nullable String parentId,
            String province,
            @Nullable String city,
            String nameEn,
            @Nullable String nameFr,
            Stage stage,
            @Nullable Double lat,
            @Nullable Double lng) {

        public boolean market() {
            return kind.equals("market");
        }
    }

    record ZoneRow(
            String id,
            String marketId,
            String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents) {}
}
