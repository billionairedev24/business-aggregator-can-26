package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.Province;
import org.jspecify.annotations.Nullable;

/** End of the Account step: creates the applicant business owned by the caller. */
public interface StartOnboarding {

    record Command(
            String userId,
            MerchantType type,
            Province province,
            @Nullable String workEmail,
            boolean businessTermsAccepted) {}

    OnboardingView start(Command command);
}
