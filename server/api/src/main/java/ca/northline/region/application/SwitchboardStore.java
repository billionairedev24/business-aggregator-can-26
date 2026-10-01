package ca.northline.region.application;

import ca.northline.region.application.Switchboard.Market;
import ca.northline.region.application.Switchboard.Zone;
import ca.northline.region.application.Switchboard.ZoneInput;
import ca.northline.region.domain.Stage;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port of the {@link Switchboard}: {@code region.regions}, {@code tax_profiles}, {@code zones}, {@code waitlist}. */
public interface SwitchboardStore {

    /** Every province, by sort. */
    List<ProvinceRow> provinces();

    Optional<ProvinceRow> province(String code);

    /** Locks the region row (province or market) for a change. */
    Optional<RegionRef> lock(String regionId);

    List<Market> markets(String provinceId);

    List<Zone> zones(String provinceId);

    Optional<ZoneRef> zone(String zoneId);

    void stage(String regionId, Stage stage);

    void courierModel(String provinceId, String model);

    boolean cityTaken(String provinceId, String city);

    /** Inserts the market (stage off) and returns its id. */
    String insertMarket(String provinceId, String province, String city, double lat, double lng, double radiusKm);

    /** Inserts or updates the zone; {@code boundary} null keeps the stored one. Fails on a malformed boundary. */
    String saveZone(@Nullable String zoneId, ZoneInput zone);

    void deleteZone(String zoneId);

    /**
     * @param tax rates in basis points by kind, empty without a profile
     */
    record ProvinceRow(
            String id,
            String code,
            Map<String, String> names,
            Stage stage,
            List<String> languages,
            @Nullable String courierModel,
            Map<String, Integer> tax,
            List<String> timeZones,
            List<String> holidays,
            @Nullable String privacyLaw,
            List<String> registries,
            long waitlist) {}

    /** @param parentId the province row of a market; null for a province */
    record RegionRef(String id, String kind, @Nullable String parentId, String province, @Nullable String city, Stage stage) {}

    record ZoneRef(String id, String marketId, String provinceId) {}
}
