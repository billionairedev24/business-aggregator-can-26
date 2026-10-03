package ca.northline.merchants.domain;

import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.MerchantSubmitted;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.EqualsAndHashCode;
import lombok.Getter;
import lombok.ToString;
import org.jspecify.annotations.Nullable;

/**
 * A business going through onboarding (the {@code merchants.merchants} row seen by the wizard, with its principals
 * and categories). Created as an applicant at the end of the Account step; each later step changes it through a
 * behaviour method. The checklist lives in {@link Verification} rows.
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public class MerchantApplication {
    /** Placeholder until the Business step names the business ({@code display_name} is NOT NULL, 2–80). */
    public static final String PLACEHOLDER_NAME = "New business";

    public static final int STARTING_TAKE_RATE_BPS = 1500;

    @EqualsAndHashCode.Include
    @ToString.Include
    private final String id;

    @ToString.Include
    private MerchantType type;

    @ToString.Include
    private MerchantStatus status;

    private OnboardingStep step;
    private @Nullable Province province;
    private @Nullable String workEmail;
    private @Nullable Instant businessTermsAcceptedAt;
    private final String createdBy;
    private final @Nullable MerchantTier tier;
    private final int takeRateBps;

    private String displayName;
    private String legalName;
    private @Nullable BusinessStructure structure;
    private @Nullable String gstNumber;
    private Map<String, Object> legalDetails;
    private List<Principal> principals;
    private List<SelectedCategory> categories;
    private BusinessProfile profile;
    private @Nullable String city;
    private List<String> languages;
    private @Nullable String businessNumber;
    private @Nullable String registryRef;
    private @Nullable String registryJurisdiction;

    private @Nullable Instant submittedAt;
    private @Nullable Instant approvedAt;
    private final Instant createdAt;
    private Instant updatedAt;

    /** End of the Account step: an applicant owned by {@code ownerId}. */
    public static MerchantApplication start(
            MerchantType type,
            Province province,
            @Nullable String workEmail,
            boolean businessTermsAccepted,
            String ownerId,
            Instant at) {
        return new MerchantApplication(
                Ids.next(),
                type,
                MerchantStatus.APPLICANT,
                OnboardingStep.BUSINESS,
                province,
                workEmail,
                businessTermsAccepted ? at : null,
                ownerId,
                MerchantTier.REGISTERED,
                STARTING_TAKE_RATE_BPS,
                PLACEHOLDER_NAME,
                "",
                null,
                null,
                Map.of(),
                List.of(),
                List.of(),
                BusinessProfile.EMPTY,
                null,
                List.of(),
                null,
                null,
                null,
                null,
                null,
                at,
                at);
    }

    /** Whether the Business step was saved at least once. */
    public boolean businessSaved() {
        return !legalName.isEmpty();
    }

    /**
     * Account step edits while still an applicant.
     *
     * @return whether the business type changed (the storefront and checklist then follow)
     */
    public boolean changeAccount(
            MerchantType newType,
            Province newProvince,
            @Nullable String newWorkEmail,
            boolean businessTermsAccepted,
            Instant at) {
        requireApplicant();
        var typeChanged = newType != type;
        type = newType;
        province = newProvince;
        workEmail = newWorkEmail;
        if (businessTermsAccepted && businessTermsAcceptedAt == null) {
            businessTermsAcceptedAt = at;
        }
        if (typeChanged) {
            categories = List.of();
        }
        updatedAt = at;
        return typeChanged;
    }

    /**
     * Business step. The details were validated against the structure's schema, principals rules and limits.
     *
     * @param cities the region model's market cities: the business's city is the first one its addresses name
     */
    public void saveBusiness(BusinessDetails details, Instant at, Collection<String> cities) {
        requireApplicant();
        displayName = details.displayName().value();
        legalName = details.legalName();
        structure = details.structure();
        gstNumber = details.gstNumber() == null ? null : details.gstNumber().value();
        legalDetails = details.legalDetails();
        principals = details.principals();
        categories = details.categories();
        profile = details.profile();
        languages = details.profile().languages();
        businessNumber = details.businessNumber();
        registryRef = details.registryRef();
        registryJurisdiction = details.registryJurisdiction(province == null ? null : province.code());
        city = Cities.find(
                        cities,
                        Stream.concat(
                                details.addresses().stream(), details.profile().places()))
                .orElse(null);
        step = step.furthest(OnboardingStep.VERIFICATION);
        updatedAt = at;
    }

    /**
     * S-120: the market the business trades in when none of its addresses named one — its pilot market, or at approval
     * its province's default market. Without a city the business belongs to no market: the shop doesn't list its
     * offers and its zone falls back to the province's. Keeps a city the addresses gave.
     */
    public void settleCity(String marketCity, Instant at) {
        if (city == null || city.isBlank()) {
            city = marketCity;
            updatedAt = at;
        }
    }

    /** Remembers how far the owner got (the wizard resumes there). */
    public void advanceTo(OnboardingStep target, Instant at) {
        if (target.needsSubmission() && status == MerchantStatus.APPLICANT) {
            throw new Conflict("not_submitted", "Submit the application for review first.");
        }
        step = step.furthest(target);
        updatedAt = at;
    }

    /** "Submit for review": applicant → pending. The caller checked the checklist and the business details. */
    public MerchantSubmitted submit(String actorId, Instant at) {
        requireApplicant();
        status = MerchantStatus.PENDING;
        submittedAt = at;
        step = step.furthest(OnboardingStep.REVIEW);
        updatedAt = at;
        return new MerchantSubmitted(Ids.next(), at, id, actorId, type.code());
    }

    /** Trust &amp; safety approval: pending → active. */
    public MerchantApproved approve(String actorId, Instant at) {
        if (status != MerchantStatus.PENDING) {
            throw new Conflict("not_pending", "Only a submitted application can be approved.");
        }
        status = MerchantStatus.ACTIVE;
        approvedAt = at;
        updatedAt = at;
        var tierCode = tier == null ? MerchantTier.REGISTERED.code() : tier.code();
        return new MerchantApproved(Ids.next(), at, id, actorId, type.code(), tierCode);
    }

    /**
     * S-79 "Request info": the agent sends a submitted application back (pending → applicant) to the Verification
     * step, where the owner fixes the reopened checks and submits again.
     */
    public void returnForInformation(Instant at) {
        if (status != MerchantStatus.PENDING) {
            throw new Conflict("not_pending", "Only a submitted application can be sent back.");
        }
        status = MerchantStatus.APPLICANT;
        step = OnboardingStep.VERIFICATION;
        updatedAt = at;
    }

    public int categoryLimit() {
        return type.categoryLimit();
    }

    private void requireApplicant() {
        if (status != MerchantStatus.APPLICANT) {
            throw new Conflict("already_submitted", "This application was already submitted.");
        }
    }
}
