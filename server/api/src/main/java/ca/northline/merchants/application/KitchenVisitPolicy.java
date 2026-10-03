package ca.northline.merchants.application;

import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.region.api.KitchenVisitRules;
import ca.northline.region.api.MerchantPlaces;
import java.util.Collection;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

/**
 * S-120: whether a kitchen is approved only after a passed visit — the region rule of its pilot market (else its city
 * market, else its province), or any of its categories needing a visit. Data, never a place in code.
 */
@Component
@RequiredArgsConstructor
class KitchenVisitPolicy {

    private final KitchenVisitRules rules;
    private final CategorySource categories;
    private final MerchantPlaces places;
    private final PilotStore pilots;

    boolean required(String merchantId, MerchantType type, @Nullable String province, Collection<String> categoryIds) {
        if (type != MerchantType.KITCHEN) {
            return false;
        }
        var pilot = pilots.byMerchant(merchantId);
        var market = pilot.isPresent()
                ? pilot.get().marketId()
                : places.of(merchantId).marketId();
        return rules.required(province, market)
                || !categories.requiringKitchenVisit(categoryIds).isEmpty();
    }

    /** For a pilot row whose market is known. */
    boolean required(String type, @Nullable String province, String marketId, Collection<String> categoryIds) {
        return MerchantType.KITCHEN.code().equals(type)
                && (rules.required(province, marketId)
                        || !categories.requiringKitchenVisit(categoryIds).isEmpty());
    }
}
