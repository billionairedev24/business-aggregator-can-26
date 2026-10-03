package ca.northline.merchants.web;

import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.OnboardingStep;
import ca.northline.merchants.domain.PrincipalRole;
import ca.northline.merchants.domain.Province;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Request bodies of the onboarding endpoints. Messages: validation-rules.md, or DECISIONS.md where the spec is silent. */
final class OnboardingRequests {
    private OnboardingRequests() {}

    static final String EMAIL = "^[^\\s@]+@[^\\s@]+\\.[^\\s@]{2,}$";
    static final String EMAIL_FORMAT = "That doesn't look like an email address.";
    static final String TYPE_REQUIRED = "Pick what your business does on Northline.";
    static final String PROVINCE_REQUIRED = "Pick the province you operate in.";
    static final String LEGAL_NAME_REQUIRED = "Enter the registered legal name.";
    static final String STRUCTURE_REQUIRED = "Pick the business structure.";
    static final String ROLE_REQUIRED = "Pick a role.";
    static final String TOO_LONG = "At most {max} characters.";
    static final String TOO_MANY = "Too many choices.";

    /** {@code POST /api/v1/merchants} and {@code PUT …/onboarding/account}. */
    record AccountRequest(
            @NotNull(message = TYPE_REQUIRED) MerchantType type,
            @NotNull(message = PROVINCE_REQUIRED) Province province,

            @Nullable @Pattern(regexp = EMAIL, message = EMAIL_FORMAT) @Size(max = 254, message = TOO_LONG)
            String workEmail,

            @Nullable Boolean businessTermsAccepted,

            // S-120: the token of a pilot invite link (read when the business is created, ignored afterwards)
            @Nullable @Size(max = 100, message = TOO_LONG) String pilotInvite) {}

    /** {@code PUT …/onboarding/business}. {@code legalDetails} uses the snake_case keys of legal-details.schema.json. */
    record BusinessRequest(
            @NotBlank(message = DisplayName.REQUIRED)
            @Size(min = DisplayName.MIN, message = DisplayName.TOO_SHORT)
            @Size(max = DisplayName.MAX, message = DisplayName.TOO_LONG)
            String displayName,

            @NotBlank(message = LEGAL_NAME_REQUIRED) @Size(max = 200, message = TOO_LONG)
            String legalName,

            @NotNull(message = STRUCTURE_REQUIRED) BusinessStructure structure,
            @Nullable String gstNumber,
            @Nullable Map<String, Object> legalDetails,
            @Nullable @Valid List<PrincipalRequest> principals,
            @Nullable @Size(max = 50, message = TOO_MANY) List<String> categoryIds,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> suggestedCategories,
            @Nullable @Valid ProfileRequest profile) {}

    record PrincipalRequest(
            @Nullable String legalName,
            @NotNull(message = ROLE_REQUIRED) PrincipalRole role,
            @Nullable BigDecimal ownershipPct) {}

    /** Business step public profile (design 02 {@code bfSets}); option fields carry codes. */
    record ProfileRequest(
            @Nullable @Size(max = 40, message = TOO_LONG) String yearsOperating,
            @Nullable @Size(max = 40, message = TOO_LONG) String teamSize,
            @Nullable @Size(max = 200, message = TOO_LONG) String serviceArea,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> workLocations,
            @Nullable @Size(max = 12, message = TOO_MANY) List<String> languages,
            @Nullable @Size(max = 200, message = TOO_LONG) String licenceNumbers,
            @Nullable @Size(max = 1000, message = TOO_LONG) String description,
            @Nullable @Size(max = 40, message = TOO_LONG) String productCount,
            @Nullable @Size(max = 200, message = TOO_LONG) String pickupAddress,
            @Nullable @Size(max = 40, message = TOO_LONG) String sameDayCutoff,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> inventorySources,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> perishables,
            @Nullable @Size(max = 20, message = TOO_MANY) List<String> cuisines,
            @Nullable @Size(max = 200, message = TOO_LONG) String kitchenAddress,
            @Nullable @Size(max = 60, message = TOO_LONG) String ahsPermitNumber,
            @Nullable @Size(max = 60, message = TOO_LONG) String cityLicenceNumber,
            @Nullable @Size(max = 40, message = TOO_LONG) String seats,
            @Nullable @Size(max = 40, message = TOO_LONG) String certifiedHandlers,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> fulfilment,
            @Nullable @Size(max = 10, message = TOO_MANY) List<String> dietary,
            @Nullable @Size(max = 40, message = TOO_LONG) String alcohol) {}

    /** {@code PATCH …/onboarding}. */
    record StepRequest(@NotNull(message = "Pick a step.") OnboardingStep step) {}

    /** {@code POST …/verifications/{id}/complete}. */
    record CompleteRequest(
            @Nullable @Size(max = 120, message = TOO_LONG) String reference,
            @Nullable String documentId,
            @Nullable LocalDate expiresOn,
            @Nullable String choice) {}
}
