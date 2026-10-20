package ca.northline.region.api;

import java.util.Map;

/**
 * S-151: the couriers' pay model as region configuration ({@code region.regions.courier_pay}). A market's own values
 * win over its province's; a key neither sets is the platform default (fulfilment's {@code courier-pay.*}). The keys
 * are {@link #KEYS}, in cents.
 */
public interface CourierPayRules {

    /** perRunCents, perPickupCents (each shop), perDropoffCents, perFailedCents, perCollectCents, perReturnCents. */
    java.util.List<String> KEYS = java.util.List.of(
            "perRunCents", "perPickupCents", "perDropoffCents", "perFailedCents", "perCollectCents", "perReturnCents");

    /**
     * The values set for the delivery market {@code market} (its city name, any case): its province's, overlaid by its
     * own. Empty when neither row sets any (or the market isn't in the region model).
     */
    Map<String, Long> forMarket(String market);
}
