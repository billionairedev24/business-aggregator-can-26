package ca.northline.shared;

import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * The console's "province and market" filter (S-91 overview, S-79… queues): a province code and/or a market id of the
 * region model (S-134) resolved to the businesses they cover ({@link MerchantScope}) and the zone their dates show in.
 * Every console screen that filters by place resolves it here, so the rules and the 422 messages are the same
 * everywhere. Implemented by the merchants module (it knows where each business operates).
 */
public interface PlaceFilter {

    String UNKNOWN_PROVINCE = "Choose a province from the list.";
    String UNKNOWN_MARKET = "Choose a market from the list.";
    String MARKET_OUTSIDE_PROVINCE = "That market isn't in the chosen province.";

    /**
     * Both blank = every business, in the platform zone. A market alone implies its province. Unknown places and a
     * market outside the chosen province are a {@link RuleViolation} on {@code province} / {@code market}.
     */
    Place resolve(@Nullable String province, @Nullable String market);

    /**
     * @param province two-letter code, or null for every province
     * @param marketId region model market id, or null
     * @param city the market's city, or null
     * @param zone the market's zone, else the province's, else the platform's
     */
    record Place(
            @Nullable String province,
            @Nullable String marketId,
            @Nullable String city,
            ZoneId zone,
            MerchantScope scope) {

        public boolean everywhere() {
            return scope.everyone();
        }
    }
}
