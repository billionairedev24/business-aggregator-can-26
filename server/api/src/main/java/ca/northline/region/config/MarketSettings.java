package ca.northline.region.config;

import ca.northline.region.api.Markets;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * {@link Markets} from {@code northline.search.markets} / {@code northline.search.default-market} (SEARCH_MARKETS,
 * SEARCH_DEFAULT_MARKET — S-44's region configuration until S-134). A malformed entry stops the api at start, as in
 * search.
 */
@Component
class MarketSettings implements Markets {

    private static final Pattern CODE = Pattern.compile("^[A-Z]{2}$");

    private final Map<String, ZoneId> zones;
    private final ZoneId fallback;
    private final @Nullable String defaultProvince;

    MarketSettings(
            @Value("${northline.search.markets}") String spec,
            @Value("${northline.search.default-market:}") String defaultMarket) {
        var parsed = parse(spec);
        if (parsed.isEmpty()) {
            throw new IllegalStateException("northline.search.markets (SEARCH_MARKETS) names no market");
        }
        var code = defaultMarket.strip().toUpperCase(Locale.ROOT);
        var preferred = parsed.get(code);
        this.zones = parsed;
        this.defaultProvince = preferred == null ? null : code;
        this.fallback =
                preferred != null ? preferred : parsed.values().iterator().next();
    }

    static Map<String, ZoneId> parse(String spec) {
        var out = new LinkedHashMap<String, ZoneId>();
        for (var entry : spec.split(",")) {
            if (entry.isBlank()) {
                continue;
            }
            var pair = entry.split("=", 2);
            var code = pair[0].strip().toUpperCase(Locale.ROOT);
            if (pair.length != 2 || !CODE.matcher(code).matches()) {
                throw new IllegalStateException(
                        "SEARCH_MARKETS entries are CODE=Time/Zone (a two-letter province or territory code), not: "
                                + entry.strip());
            }
            try {
                out.put(code, ZoneId.of(pair[1].strip()));
            } catch (DateTimeException e) {
                throw new IllegalStateException("SEARCH_MARKETS: " + code + " has no valid time zone: " + pair[1], e);
            }
        }
        return out;
    }

    @Override
    public Set<String> served() {
        return java.util.Collections.unmodifiableSet(zones.keySet());
    }

    @Override
    public boolean serves(@Nullable String province) {
        return province != null && zones.containsKey(province.strip().toUpperCase(Locale.ROOT));
    }

    @Override
    public @Nullable String defaultProvince() {
        return defaultProvince;
    }

    @Override
    public ZoneId zone(@Nullable String province) {
        if (province == null) {
            return fallback;
        }
        var zone = zones.get(province.strip().toUpperCase(Locale.ROOT));
        return zone != null ? zone : fallback;
    }
}
