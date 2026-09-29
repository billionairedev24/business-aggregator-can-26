package ca.northline.merchants.application;

import ca.northline.merchants.domain.BusinessSettings;
import java.time.Instant;
import java.util.Optional;

/**
 * Outbound port: Settings › Business columns of {@code merchants.merchants} (+ {@code profile.serviceArea}). The display
 * name is written through {@link MerchantRepository} (it publishes {@code merchant.renamed}).
 */
public interface BusinessSettingsStore {

    Optional<BusinessSettings> find(String merchantId);

    /** Everything except the display name. */
    void save(BusinessSettings settings, Instant at);

    /** A changed legal name or GST number sends the matching onboarding check back to review. */
    void reopenCheck(String merchantId, String checkKey, Instant at);
}
