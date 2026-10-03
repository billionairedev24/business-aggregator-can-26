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
            boolean businessTermsAccepted,
            @Nullable String pilotInvite) {

        /** Without a pilot invite (S-120). */
        public Command(
                String userId,
                MerchantType type,
                Province province,
                @Nullable String workEmail,
                boolean businessTermsAccepted) {
            this(userId, type, province, workEmail, businessTermsAccepted, null);
        }
    }

    OnboardingView start(Command command);
}
