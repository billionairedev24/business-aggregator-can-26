package ca.northline.payments.infra;

import ca.northline.payments.api.MerchantBillingFacts;
import ca.northline.payments.application.MerchantTiers;
import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.payments.domain.Tier;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/** {@link MerchantTiers} over the merchants module's answer ({@link MerchantBillingFacts}, S-37). */
@Component
@RequiredArgsConstructor
class MerchantTierLookup implements MerchantTiers {

    private final MerchantBillingFacts facts;

    @Override
    public Rate rateOf(String merchantId) {
        return facts.billing(merchantId)
                .map(b -> {
                    var tier = Tier.of(b.tier());
                    var bps = b.takeRateBps();
                    return new Rate(tier, bps == null ? tier.takeRateBps() : bps);
                })
                .orElse(new Rate(Tier.REGISTERED, Tier.REGISTERED.takeRateBps()));
    }

    @Override
    public Optional<Province> provinceOf(String merchantId) {
        return facts.billing(merchantId)
                .flatMap(b -> Optional.ofNullable(b.province()))
                .flatMap(Province::of);
    }
}
