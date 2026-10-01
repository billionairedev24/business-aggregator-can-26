package ca.northline.region.api;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Writes to the region model for the console's province switchboard (S-84): {@code region.regions} (stage, courier
 * model, new markets), {@code region.zones}, and the reads the switchboard needs (tax profile, waitlist). The console
 * module orchestrates, audits and calls {@link Regions#refresh()} after commit; only the region module touches the
 * tables.
 */
public interface RegionEditor {

    /** Every province, by sort. */
    List<ProvinceRow> provinces();

    Optional<ProvinceRow> province(String code);

    /** Locks the region row (province or market) for a change. */
    Optional<RegionRef> lock(String regionId);

    List<Market> markets(String provinceId);

    List<Zone> zones(String provinceId);

    Optional<ZoneRef> zone(String zoneId);

    void stage(String regionId, LaunchStatus stage);

    void courierModel(String provinceId, String model);

    boolean cityTaken(String provinceId, String city);

    /** Inserts the market (stage off) and returns its id. */
    String insertMarket(String provinceId, String province, String city, double lat, double lng, double radiusKm);

    /** Inserts or updates the zone; {@code boundary} null keeps the stored one. @throws IllegalArgumentException on a malformed boundary */
    String saveZone(@Nullable String zoneId, ZoneInput zone);

    void deleteZone(String zoneId);

    /**
     * @param tax rates in basis points by kind, empty without a profile
     */
    record ProvinceRow(
            String id,
            String code,
            Map<String, String> names,
            LaunchStatus stage,
            List<String> languages,
            @Nullable String courierModel,
            Map<String, Integer> tax,
            List<String> timeZones,
            List<String> holidays,
            @Nullable String privacyLaw,
            List<String> registries,
            long waitlist) {}

    /** @param parentId the province row of a market; null for a province */
    record RegionRef(
            String id,
            String kind,
            @Nullable String parentId,
            String province,
            @Nullable String city,
            LaunchStatus stage) {}

    record ZoneRef(String id, String marketId, String provinceId) {}

    record Market(
            String id,
            String city,
            LaunchStatus stage,
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable Double radiusKm,
            int zones,
            long waitlist) {}

    /** @param areaKm2 the boundary's area, null without one */
    record Zone(
            String id,
            String marketId,
            String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents,
            @Nullable Double areaKm2) {}

    /**
     * @param boundary a GeoJSON Polygon (or a Feature with one), lng/lat; null keeps the current boundary (none when new)
     */
    record ZoneInput(
            String marketId,
            String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents,
            @Nullable String boundary) {}
}
