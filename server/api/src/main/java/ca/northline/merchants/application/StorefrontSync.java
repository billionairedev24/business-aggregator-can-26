package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;

/** How onboarding keeps the storefront in step with the business (implemented by the storefront service). */
interface StorefrontSync {
    /** Creates the page with the recommended sections the first time the business is named. */
    void ensure(String merchantId, MerchantType type, String displayName);

    /** The business type changed while an applicant: new page kind, recommended sections again. */
    void typeChanged(String merchantId, MerchantType type);
}
