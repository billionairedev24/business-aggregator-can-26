package ca.northline.food.adapters;

import ca.northline.food.application.KitchenMerchantFacts;
import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.merchants.api.MerchantDirectory.MerchantProfile;
import ca.northline.merchants.api.MerchantVerifications;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link KitchenMerchantFacts} from the merchants module's public API (S-37). */
@Component
@RequiredArgsConstructor
class DirectoryKitchenMerchantFacts implements KitchenMerchantFacts {

    private final MerchantDirectory directory;
    private final MerchantVerifications verifications;

    @Override
    public boolean approved(String merchantId) {
        return directory.profile(merchantId).filter(MerchantProfile::active).isPresent();
    }

    @Override
    public FoodSafety foodSafety(String merchantId) {
        return new FoodSafety(
                evidence(merchantId, "ahs_permit"),
                evidence(merchantId, "food_cert"),
                evidence(merchantId, "inspection"));
    }

    private Evidence evidence(String merchantId, String checkType) {
        return verifications
                .latest(merchantId, checkType)
                .map(e -> new Evidence(e.reference(), e.status(), e.expiresAt()))
                .orElse(Evidence.NONE);
    }
}
