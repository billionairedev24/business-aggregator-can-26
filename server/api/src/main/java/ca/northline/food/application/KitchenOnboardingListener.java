package ca.northline.food.application;

import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.MerchantSubmitted;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Kitchens only: on {@code merchant.submitted} create the starter menu; on {@code merchant.approved} re-audit the
 * published items so the ones with allergens and a photo go live. Async, own transaction, idempotent.
 */
@Component
@RequiredArgsConstructor
class KitchenOnboardingListener {

    private static final String KITCHEN = "kitchen";

    private final KitchenProvisioning provisioning;

    @ApplicationModuleListener
    void on(MerchantSubmitted submitted) {
        if (KITCHEN.equals(submitted.merchantType())) {
            provisioning.starterMenu(submitted.aggregateId());
        }
    }

    @ApplicationModuleListener
    void on(MerchantApproved approved) {
        if (KITCHEN.equals(approved.merchantType())) {
            provisioning.reaudit(approved.aggregateId());
        }
    }
}
