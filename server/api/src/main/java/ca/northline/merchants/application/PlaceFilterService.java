package ca.northline.merchants.application;

import ca.northline.merchants.api.MarketplaceMerchants;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.PlaceFilter;
import ca.northline.shared.RuleViolation;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

/**
 * {@link PlaceFilter} over the region model and where businesses operate ({@code merchants.province}, the market's
 * city): the console's province / market filter (S-91, moved here from the overview by S-79 so every queue shares it).
 */
@Service
@RequiredArgsConstructor
class PlaceFilterService implements PlaceFilter {

    private final Regions regions;
    private final MarketplaceMerchants merchants;

    @Override
    public Place resolve(@Nullable String provinceCode, @Nullable String marketId) {
        var market = market(marketId);
        var province = province(provinceCode, market);
        if (province == null && market == null) {
            return new Place(null, null, null, regions.platformZone(), MerchantScope.everyBusiness());
        }
        var city = market == null ? null : market.city();
        var zone = market != null ? market.zone() : regions.zone(province, null);
        return new Place(
                province,
                market == null ? null : market.id(),
                city,
                zone,
                MerchantScope.only(merchants.idsIn(province, city)));
    }

    private @Nullable MarketProfile market(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return regions.marketById(id.strip()).orElseThrow(() -> RuleViolation.of("market", "exists", UNKNOWN_MARKET));
    }

    private @Nullable String province(@Nullable String code, @Nullable MarketProfile market) {
        if (code == null || code.isBlank()) {
            return market == null ? null : market.province();
        }
        var province = regions.province(code.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> RuleViolation.of("province", "exists", UNKNOWN_PROVINCE));
        if (market != null && !market.province().equals(province.code())) {
            throw RuleViolation.of("market", "province", MARKET_OUTSIDE_PROVINCE);
        }
        return province.code();
    }
}
