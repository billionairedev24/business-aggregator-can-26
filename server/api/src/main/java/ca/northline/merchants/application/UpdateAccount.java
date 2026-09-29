package ca.northline.merchants.application;

import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.Province;
import org.jspecify.annotations.Nullable;

/** Account step edits while still an applicant (type, province, work email). */
public interface UpdateAccount {

    record Command(
            String merchantId,
            MerchantType type,
            Province province,
            @Nullable String workEmail,
            boolean businessTermsAccepted) {}

    OnboardingView update(Command command);
}
