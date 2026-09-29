package ca.northline.merchants.application;

import ca.northline.merchants.domain.BusinessProfile;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.Principal;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Business step: names, structure, GST, legal details, principals, categories and the public profile. Validates
 * everything (validation-rules.md › Business step) and rebuilds the verification checklist.
 */
public interface SaveBusiness {

    record Command(
            String merchantId,
            String displayName,
            String legalName,
            BusinessStructure structure,
            @Nullable String gstNumber,
            Map<String, Object> legalDetails,
            List<Principal> principals,
            List<String> categoryIds,
            List<String> suggestedCategories,
            BusinessProfile profile) {}

    OnboardingView save(Command command);
}
