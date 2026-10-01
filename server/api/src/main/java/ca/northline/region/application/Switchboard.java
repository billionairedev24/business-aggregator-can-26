package ca.northline.region.application;

import ca.northline.region.domain.Stage;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The console's province switchboard (S-84, design 03 {@code regions}): provinces, their markets and delivery zones
 * with their stages (off · waitlist · pilot · live). Changes go to {@code region.regions} / {@code region.zones}, are
 * audited, and the region model is re-read after commit ({@code Regions.refresh()}). Admins only.
 */
public interface Switchboard {

    String CONFIRM_PROVINCE = "Type the province code to confirm.";
    String CONFIRM_MARKET = "Type the market's name to confirm.";
    String STAGE = "Choose off, waitlist, pilot or live.";
    String ABOVE_PROVINCE = "A market can't be more open than its province.";
    String NOT_READY = "Complete the checklist before going live.";
    String MARKET_NOT_READY = "Add a delivery zone with a boundary before the market goes live.";
    String COURIER_MODEL = "Choose own fleet, contracted or hybrid.";
    String CITY = "Enter the city or area, 2 to 60 characters.";
    String CITY_TAKEN = "That province already has a market with this name.";
    String CENTRE = "Give the market's centre as a latitude and longitude in Canada.";
    String RADIUS = "The radius is 1 to 200 km.";
    String ZONE_NAME = "Enter the zone's name, 1 to 60 characters.";
    String ZONE_MARKET = "Choose one of the province's markets.";
    String RUNS = "Pooled runs a day are 0 to 24.";
    String MONEY = "Enter an amount of $0 or more.";
    String BOUNDARY = "Paste a GeoJSON polygon (lng, lat) for the boundary.";
    String LAST_ZONE = "A live market keeps at least one zone. Lower its stage first.";

    Board board();

    Province provinceStage(String code, Stage stage, String confirm, Actor actor);

    Province courierModel(String code, String model, Actor actor);

    Province marketStage(String marketId, Stage stage, String confirm, Actor actor);

    Province addMarket(NewMarket market, Actor actor);

    /** @param zoneId null to create */
    Province saveZone(@Nullable String zoneId, ZoneInput zone, Actor actor);

    Province removeZone(String zoneId, Actor actor);

    /** @param role the console roles acted with */
    record Actor(String userId, String role) {}

    record NewMarket(String province, String city, double lat, double lng, double radiusKm) {}

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

    record Board(List<Province> provinces) {
        public Board {
            provinces = List.copyOf(provinces);
        }
    }

    /**
     * @param names {@code en}, {@code fr}
     * @param tax the tax profile's rates in basis points ({@code gst}, {@code pst}, {@code hst}, {@code qst}), empty
     *     without a profile
     * @param holidays statutory holiday keys
     * @param checklist what going live needs: {@code taxProfile}, {@code holidays}, {@code registries},
     *     {@code marketWithZones}
     */
    record Province(
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
            long waitlist,
            List<Market> markets,
            List<Zone> zones,
            Map<String, Boolean> checklist) {

        public Province {
            names = Map.copyOf(names);
            languages = List.copyOf(languages);
            tax = Map.copyOf(tax);
            timeZones = List.copyOf(timeZones);
            holidays = List.copyOf(holidays);
            registries = List.copyOf(registries);
            markets = List.copyOf(markets);
            zones = List.copyOf(zones);
            checklist = Map.copyOf(checklist);
        }

        public boolean ready() {
            return checklist.values().stream().allMatch(Boolean::booleanValue);
        }
    }

    record Market(
            String id,
            String city,
            Stage stage,
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
}
