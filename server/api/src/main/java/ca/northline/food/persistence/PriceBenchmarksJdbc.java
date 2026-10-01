package ca.northline.food.persistence;

import ca.northline.food.application.PriceBenchmarks;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.merchants.api.PublicDirectory.PublicBusiness;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link PriceBenchmarks}: the peers come from the merchants module (active kitchens of the kitchen's city, the
 * market's key, sharing one of its public cuisines), the prices from food's own menus. A kitchen without public
 * cuisines (not approved yet) isn't compared.
 */
@Repository
@RequiredArgsConstructor
class PriceBenchmarksJdbc implements PriceBenchmarks {

    private static final Set<String> KITCHENS = Set.of("kitchen");

    private final JdbcClient jdbc;
    private final MerchantDirectory merchants;
    private final PublicDirectory directory;

    @Override
    public @Nullable Long median(String merchantId) {
        var city = merchants.profile(merchantId).map(MerchantDirectory.MerchantProfile::city).orElse(null);
        if (city == null || city.isBlank()) {
            return null;
        }
        var cuisines = directory.byId(merchantId).map(PublicBusiness::cuisines).orElse(List.of());
        if (cuisines.isEmpty()) {
            return null; // not approved yet, or no cuisine to compare with: checked again on approval (reaudit)
        }
        var peers = directory.active(KITCHENS, city).stream()
                .filter(b -> !b.merchantId().equals(merchantId))
                .filter(b -> b.cuisines().stream().anyMatch(cuisines::contains))
                .map(PublicBusiness::merchantId)
                .toList();
        if (peers.isEmpty()) {
            return null;
        }
        return jdbc.sql("""
                        select case when count(*) >= :min
                                    then round(percentile_cont(0.5) within group (order by i.price_cents))::bigint end
                          from food.menu_items i
                          join food.menu_sections s on s.id = i.section_id
                          join food.menus m on m.id = s.menu_id
                         where m.merchant_id in (:peers) and m.status = 'live'
                           and i.status = 'published' and i.vetting = 'approved' and i.price_cents > 0
                        """)
                .param("min", MIN_DISHES)
                .param("peers", peers)
                .query(Long.class)
                .optional()
                .orElse(null);
    }
}
