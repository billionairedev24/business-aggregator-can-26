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

    /** Every province and market with its profile columns (V130), provinces first, each by sort. */
    List<ProfileRow> profiles();

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

    /**
     * A {@code region.regions} row with its profile (S-134).
     *
     * @param timeZones IANA zone ids as stored (a market's may be empty: it keeps its province's)
     * @param taxBps the province's current combined tax rate, null without a tax profile
     * @param frIn / {@code frOf}: the French name with its preposition / article ("en Alberta", "de l'Alberta")
     */
    record ProfileRow(
            RegionRow region,
            List<String> timeZones,
            List<String> holidays,
            @Nullable String privacyLaw,
            List<String> registries,
            @Nullable Integer taxBps,
            @Nullable String frIn,
            @Nullable String frOf) {}

    record ZoneRow(
            String id,
            String marketId,
            String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents) {}
}
