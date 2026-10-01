package ca.northline.search.application;

import ca.northline.region.api.Markets;
import java.time.Duration;
import java.time.ZoneId;
import org.jspecify.annotations.Nullable;

/**
 * Settings the application layer needs (bound from {@code northline.search.*} in the integration package). The markets
 * search serves, their time zones and the default market are the region model's ({@link Markets}, S-134): a province
 * opens for search the moment the region configuration serves it.
 */
public record SearchSettings(Duration cacheTtl, int rateLimitPerMinute, Markets markets) {

    /** The market searched when a request names none; {@code null} = requests must name one. */
    public @Nullable String defaultMarket() {
        return markets.defaultProvince();
    }

    public boolean serves(String market) {
        return markets.serves(market);
    }

    /** The time zone the market's listings keep their hours, cut-offs and "sold out today" dates in. */
    public ZoneId zone(String market) {
        return markets.zone(market);
    }
}
