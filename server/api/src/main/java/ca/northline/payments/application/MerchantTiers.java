package ca.northline.payments.application;

import ca.northline.payments.domain.Tier;

/**
 * Outbound port: the merchant's tier and take rate (owned by merchants — {@code merchants.merchants.tier} and
 * {@code take_rate_bps}; read-only here). The take rate decides Northline's fee when money is held.
 */
public interface MerchantTiers {

    /** {@code takeRateBps} = the merchant's own rate, or the tier's default when none is set. */
    record Rate(Tier tier, int takeRateBps) {}

    Rate rateOf(String merchantId);

    default Tier tierOf(String merchantId) {
        return rateOf(merchantId).tier();
    }
}
