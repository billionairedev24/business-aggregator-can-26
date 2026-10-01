package ca.northline.console.application;

import ca.northline.merchants.api.MarketplaceMerchants;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.RuleViolation;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * A console screen's {@code ?province=&market=} (region model ids) as the businesses they cover — the overview's rule
 * (S-91) for the other composed screens: unknown values and a market outside the province are 422s.
 */
@Component
@RequiredArgsConstructor
class PlaceScope {

    private final Regions regions;
    private final MarketplaceMerchants merchants;

    /** @param market the region market, when one was asked for */
    record Resolved(@Nullable String province, @Nullable MarketProfile market, MerchantScope scope) {}

    Resolved resolve(@Nullable String provinceCode, @Nullable String marketId) {
        var market = market(marketId);
        var province = province(provinceCode, market);
        var scope = province == null && market == null
                ? MerchantScope.everyBusiness()
                : MerchantScope.only(merchants.idsIn(province, market == null ? null : market.city()));
        return new Resolved(province, market, scope);
    }

    private @Nullable MarketProfile market(@Nullable String id) {
        if (id == null || id.isBlank()) {
            return null;
        }
        return regions.marketById(id.strip())
                .orElseThrow(() -> RuleViolation.of("market", "exists", OverviewService.UNKNOWN_MARKET));
    }

    private @Nullable String province(@Nullable String code, @Nullable MarketProfile market) {
        if (code == null || code.isBlank()) {
            return market == null ? null : market.province();
        }
        var province = regions.province(code.strip().toUpperCase(Locale.ROOT))
                .orElseThrow(() -> RuleViolation.of("province", "exists", OverviewService.UNKNOWN_PROVINCE));
        if (market != null && !market.province().equals(province.code())) {
            throw RuleViolation.of("market", "province", OverviewService.MARKET_OUTSIDE_PROVINCE);
        }
        return province.code();
    }
}
