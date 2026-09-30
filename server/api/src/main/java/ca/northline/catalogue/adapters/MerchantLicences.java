package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.LicenceRegistry;
import ca.northline.merchants.api.MerchantVerifications;
import java.time.Clock;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Licence check for regulated categories: a verified, unexpired licence or registry check for the category's
 * registry, asked of the merchants module (S-37).
 */
@Component
@RequiredArgsConstructor
class MerchantLicences implements LicenceRegistry {

    private final MerchantVerifications verifications;
    private final Clock clock;

    @Override
    public boolean hasVerifiedLicence(String merchantId, String registry) {
        return verifications.hasVerifiedLicence(merchantId, registry, clock.instant());
    }
}
