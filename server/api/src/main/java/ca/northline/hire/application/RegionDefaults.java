package ca.northline.hire.application;

import ca.northline.region.api.FallbackMarket;
import ca.northline.region.api.Markets;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Where the Services journey's region facts come from (DECISIONS "Region-neutral by design"): the business's own
 * province, else the configured default market ({@code region.api.Markets}); the province's time zone; and the api's
 * fallback market for a visitor without a location (S-47). Nothing here names a place.
 */
@Component
@RequiredArgsConstructor
public class RegionDefaults {

    private final Markets markets;
    private final FallbackMarket fallbackMarket;

    /** The province a business's work is taxed in: its own, else the default market's, else the first served. */
    public String province(@Nullable String own) {
        if (own != null && !own.isBlank()) {
            return own;
        }
        var fallback = markets.defaultProvince();
        return fallback != null ? fallback : markets.served().iterator().next();
    }

    /** The time zone of the province's market (the default market's for an unknown province). */
    public ZoneId zone(@Nullable String province) {
        return markets.zone(province);
    }

    /** The city to look in when the customer has no location at all; null when no fallback market is configured. */
    public @Nullable String fallbackCity() {
        return fallbackMarket.fallback().map(FallbackMarket.City::city).orElse(null);
    }
}
