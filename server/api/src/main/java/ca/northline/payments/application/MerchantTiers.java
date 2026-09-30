package ca.northline.payments.application;

import ca.northline.payments.domain.CanadianTax.Province;
import ca.northline.payments.domain.Tier;
import java.util.Optional;

/**
 * Outbound port: the merchant's tier, take rate and province (owned by merchants, read through
 * {@link ca.northline.payments.api.MerchantBillingFacts} — S-37). The take rate decides Northline's fee when money is
 * held; the province is the place of supply when checkout didn't report one.
 */
public interface MerchantTiers {

    /** {@code takeRateBps} = the merchant's own rate, or the tier's default when none is set. */
    record Rate(Tier tier, int takeRateBps) {}

    Rate rateOf(String merchantId);

    Optional<Province> provinceOf(String merchantId);

    default Tier tierOf(String merchantId) {
        return rateOf(merchantId).tier();
    }
}
