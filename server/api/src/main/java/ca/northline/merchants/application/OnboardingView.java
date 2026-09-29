package ca.northline.merchants.application;

import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.Verification;
import java.util.List;
import java.util.Map;

/** Read model of the onboarding wizard: the application, its checklist and the documents they reference. */
public record OnboardingView(
        MerchantApplication application, List<Verification> verifications, Map<String, Document> documents) {
    public OnboardingView {
        verifications = List.copyOf(verifications);
        documents = Map.copyOf(documents);
    }
}
