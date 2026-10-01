package ca.northline.search.application;

import java.time.Duration;
import java.time.ZoneId;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Settings the application layer needs (bound from {@code northline.search.*} in the integration package).
 *
 * @param defaultMarket the market searched when a request names none; {@code null} = requests must name one
 * @param markets the markets search serves (province/territory code) → the time zone their listings' hours, cut-offs
 *     and "sold out today" dates are kept in. Region configuration, not code: S-134 moves it into the region config.
 */
public record SearchSettings(
        Duration cacheTtl, @Nullable String defaultMarket, int rateLimitPerMinute, Map<String, ZoneId> markets) {

    public SearchSettings {
        markets = Map.copyOf(markets);
        if (markets.isEmpty()) {
            throw new IllegalStateException("northline.search.markets (SEARCH_MARKETS) names no market");
        }
        if (defaultMarket != null && !markets.containsKey(defaultMarket)) {
            throw new IllegalStateException(
                    "SEARCH_DEFAULT_MARKET " + defaultMarket + " is not one of SEARCH_MARKETS " + markets.keySet());
        }
    }

    public boolean serves(String market) {
        return markets.containsKey(market);
    }

    public ZoneId zone(String market) {
        return Objects.requireNonNull(markets.get(market), () -> "not a served market: " + market);
    }
}
