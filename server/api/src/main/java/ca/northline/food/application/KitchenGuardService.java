package ca.northline.food.application;

import ca.northline.food.application.KitchenUseCases.RequireKitchen;
import ca.northline.shared.NotFound;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** {@link RequireKitchen} from the merchants directory (S-73). */
@Service
@RequiredArgsConstructor
class KitchenGuardService implements RequireKitchen {

    private final KitchenMerchantFacts merchants;

    @Override
    public void require(String merchantId) {
        if (!merchants.kitchen(merchantId)) {
            throw new NotFound("kitchen", merchantId);
        }
    }
}
