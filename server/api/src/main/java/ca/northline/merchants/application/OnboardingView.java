package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Verification;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;

/**
 * Read model of the onboarding wizard: the application, its checklist and the documents they reference.
 *
 * @param zone the business's time zone (region model): the zone expiry dates are shown in
 */
public record OnboardingView(
        MerchantApplication application,
        List<Verification> verifications,
        Map<String, Document> documents,
        ZoneId zone) {
    public OnboardingView {
        verifications = List.copyOf(verifications);
        documents = Map.copyOf(documents);
    }
}
