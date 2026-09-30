package ca.northline.merchants.integration;

import ca.northline.merchants.api.MerchantDirectory;
import ca.northline.payments.api.MerchantBillingFacts;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** Answers payments' {@link MerchantBillingFacts} from the merchants directory (S-37). */
@Component
@RequiredArgsConstructor
class PaymentsBillingFacts implements MerchantBillingFacts {

    private final MerchantDirectory directory;

    @Override
    public Optional<Billing> billing(String merchantId) {
        return directory.profile(merchantId).map(p -> new Billing(p.tier(), p.takeRateBps(), p.province()));
    }
}
