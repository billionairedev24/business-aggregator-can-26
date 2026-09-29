package ca.northline.merchants.web;

import ca.northline.merchants.domain.BusinessProfile;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.CheckType;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.OnboardingStep;
import ca.northline.merchants.domain.PrincipalRole;
import ca.northline.merchants.domain.Province;
import ca.northline.merchants.domain.VerificationStatus;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Response bodies of the onboarding endpoints. */
final class OnboardingResponses {
    private OnboardingResponses() {}

    /** {@code GET …/onboarding} and every onboarding write. {@code business} is null until the Business step was saved. */
    record OnboardingResponse(
            String merchantId,
            MerchantType type,
            MerchantStatus status,
            OnboardingStep step,
            @Nullable Province province,
            @Nullable String workEmail,
            boolean businessTermsAccepted,
            String displayName,
            @Nullable String city,
            @Nullable BusinessResponse business,
            List<CheckResponse> checklist,
            int checksComplete,
            @Nullable Instant submittedAt,
            @Nullable Instant approvedAt) {}

    record BusinessResponse(
            String displayName,
            String legalName,
            @Nullable BusinessStructure structure,
            @Nullable String gstNumber,
            Map<String, Object> legalDetails,
            List<PrincipalResponse> principals,
            List<CategoryResponse> categories,
            BusinessProfile profile,
            List<DocumentResponse> documents) {}

    record PrincipalResponse(
            String legalName, PrincipalRole role, @Nullable BigDecimal ownershipPct) {}

    record CategoryResponse(
            String id, String name, @Nullable String regulator, boolean suggested) {}

    /** One verification check; {@code key} identifies the design row ({@code licence:AMVIC}, {@code gst} …). */
    record CheckResponse(
            String id,
            String key,
            CheckType checkType,
            CheckKind.Action action,
            @Nullable String registry,
            VerificationStatus status,
            @Nullable String reference,
            @Nullable DocumentResponse document,
            @Nullable LocalDate expiresOn,
            Instant updatedAt) {}

    record DocumentResponse(String id, String fileName, String contentType, long sizeBytes) {}

    /** {@code GET /api/v1/onboarding/taxonomy}. */
    record TaxonomyResponse(MerchantType type, int limit, List<GroupResponse> groups) {}

    record GroupResponse(String id, String name, String note, List<ItemResponse> items) {}

    record ItemResponse(String id, String name, @Nullable String regulator) {}
}
