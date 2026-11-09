package ca.northline.hire.application;

import ca.northline.region.api.Markets;
import java.time.ZoneId;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * Where the Services journey's region facts come from (DECISIONS "Region-neutral by design"): the business's own
 * province, else the configured default market ({@code region.api.Markets}); the province's time zone (S-190: no fallback market for a visitor without a
 * location any more). Nothing here names a place.
 */
@Component
@RequiredArgsConstructor
public class RegionDefaults {

    private final Markets markets;

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
}
