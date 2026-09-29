package ca.northline.food.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: what the kitchen screens need to know about the business itself — whether Northline approved it
 * (items go live only then) and the food-safety evidence from its verification checklist ({@code merchants.verifications};
 * renewals are handled on the compliance screen).
 */
public interface KitchenMerchantFacts {

    boolean approved(String merchantId);

    FoodSafety foodSafety(String merchantId);

    /**
     * @param reference permit number / "3 staff"
     * @param status todo | submitted | verified | expired | rejected (null = no such check)
     */
    record Evidence(
            @Nullable String reference,
            @Nullable String status,
            @Nullable Instant expiresAt) {
        public static final Evidence NONE = new Evidence(null, null, null);
    }

    record FoodSafety(Evidence permit, Evidence handlers, Evidence inspection) {}
}
