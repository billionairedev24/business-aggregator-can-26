package ca.northline.region.api;

import java.time.ZoneId;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * A city market inside a province (region data): where Northline delivers, its time zone (its own, else its
 * province's), its centre and launch status.
 *
 * @param registries business-registry adapter keys serving the city only (e.g. a municipal licence dataset)
 * @param language the market's language rules: its own where the region row sets them, else its province's (S-116)
 */
public record MarketProfile(
        String id,
        String city,
        String province,
        ZoneId zone,
        @Nullable Double lat,
        @Nullable Double lng,
        LaunchStatus status,
        List<String> registries,
        LanguageRules language) {

    public MarketProfile {
        registries = List.copyOf(registries);
    }

    public boolean live() {
        return status.live();
    }
}
