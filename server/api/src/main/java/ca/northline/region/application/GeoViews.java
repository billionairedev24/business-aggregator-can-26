package ca.northline.region.application;

import ca.northline.region.domain.Stage;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Read models of the Location screen and the location pill, serialized as they are (S-47). */
public final class GeoViews {
    private GeoViews() {}

    public record Market(String id, String city, String province, Stage stage) {}

    /**
     * A delivery zone and its pooled-run pricing ("3 pooled runs / day").
     *
     * @param feeStdCents pooled delivery; {@code feePlusCents} with Northline Plus
     */
    public record Zone(
            String id,
            String name,
            @Nullable Integer runsPerDay,
            @Nullable Long feeStdCents,
            @Nullable Long feePlusCents,
            @Nullable Long minBasketCents) {}

    /** Where Northline stands for a place Northline doesn't serve (a market or a province). */
    public record Waitlist(String regionId, String name, Stage stage) {}

    /**
     * What an address or point means for Northline.
     *
     * @param market the market covering it (any stage), or null outside every market
     * @param zone its delivery zone inside a live or pilot market, or null
     * @param waitlist the list to join when it isn't in a live market ("An address outside a live market joins the
     *     waitlist for its nearest one"): the covering market, else the province's nearest market, else the province
     */
    public record Resolution(
            @Nullable Market market,
            @Nullable Zone zone,
            @Nullable Waitlist waitlist) {

        public boolean live() {
            return market != null && market.stage().live();
        }
    }

    public record Suggestions(List<Suggestion> items, String attribution) {
        public Suggestions {
            items = List.copyOf(items);
        }
    }

    public record Suggestion(String placeId, String main, String secondary) {}

    /**
     * A chosen address: {@code label} is what the pill shows ("1204 17 Ave SW, Calgary").
     */
    public record Address(
            String placeId,
            String label,
            String street,
            @Nullable String city,
            @Nullable String province,
            @Nullable String postalCode,
            @Nullable String neighbourhood,
            double lat,
            double lng,
            Resolution resolution) {}

    /**
     * The device's position named for the pill: {@code label} = "Beltline, Calgary" (neighbourhood or zone, city);
     * {@code city} = the market's city inside a market, else the locality.
     */
    public record Place(
            String label,
            String city,
            @Nullable String province,
            @Nullable Market market,
            @Nullable Zone zone) {}

    /** A province button of the Location screen with its markets (by sort). */
    public record Province(String code, String name, Stage stage, List<Market> markets) {
        public Province {
            markets = List.copyOf(markets);
        }
    }
}
