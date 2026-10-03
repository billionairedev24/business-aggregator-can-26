package ca.northline.merchants.application;

import ca.northline.merchants.application.ManageApplication.AdvanceOnboarding;
import ca.northline.merchants.application.ManageApplication.ApproveApplication;
import ca.northline.merchants.application.ManageApplication.SubmitApplication;
import ca.northline.merchants.application.ManageApplication.ViewOnboarding;
import ca.northline.merchants.domain.BusinessDetails;
import ca.northline.merchants.domain.BusinessStructure;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.CheckSpec;
import ca.northline.merchants.domain.DisplayName;
import ca.northline.merchants.domain.Document;
import ca.northline.merchants.domain.GstNumber;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantType;
import ca.northline.merchants.domain.OnboardingStep;
import ca.northline.merchants.domain.Principal;
import ca.northline.merchants.domain.PrincipalRole;
import ca.northline.merchants.domain.Province;
import ca.northline.merchants.domain.SelectedCategory;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The onboarding wizard's write side. Each step validates, changes the {@link MerchantApplication}, keeps the
 * verification checklist in line with type + categories, and (Submit / Approve) publishes its event in the same
 * transaction.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class OnboardingService
        implements StartOnboarding,
                UpdateAccount,
                SaveBusiness,
                ViewOnboarding,
                AdvanceOnboarding,
                SubmitApplication,
                ApproveApplication {

    static final String CATEGORIES = "categories";
    static final String PICK_SERVICE = "Pick at least one service.";
    static final String PICK_DEPARTMENT = "Pick at least one department.";
    static final String MAXIMUM = "Maximum %d.";
    static final String UNKNOWN_CATEGORY = "Pick categories from the list, or suggest a new one.";
    static final String BN_TAKEN = "That business number is already registered on Northline.";
    static final String BUSINESS_FIRST = "Complete the Business step first.";
    static final String CHECKS_FIRST = "Complete every check before submitting.";
    static final int SUGGESTION_MAX = 60;

    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final DocumentRepository documents;
    private final Taxonomy taxonomy;
    private final LegalDetailsSchema legalSchema;
    private final StorefrontSync storefronts;
    private final OwnerIdentityService ownerIdentity;
    private final ApplicationEventPublisher events;
    private final Clock clock;
    private final MerchantPlaces places;
    private final Regions regions;
    private final CategoryLimitLookup categoryLimits;
    private final PilotInviteLinks pilotInvites;
    private final KitchenVisitPolicy kitchenVisits;
    private final PilotStore pilots;

    /** S-120: approving a kitchen that needs a visit before the visit passed. */
    static final String VISIT_FIRST = "Record a passed kitchen visit before approving this kitchen.";

    /** {@code {province}} = the province's name (region model). */
    static final String PROVINCE_CLOSED = "Northline isn't open in {province} yet.";

    /** The province must be open in the region model: live, pilot or waitlist (S-134). */
    private void requireOpen(Province province) {
        var profile = regions.province(province.code());
        if (profile.isEmpty() || profile.get().status() == LaunchStatus.OFF) {
            throw RuleViolation.of(
                    "province",
                    "option",
                    PROVINCE_CLOSED.replace(
                            "{province}", profile.map(p -> p.nameEn()).orElse(province.code())));
        }
    }

    @Override
    @Transactional
    public OnboardingView start(StartOnboarding.Command command) {
        requireOpen(command.province());
        pilotInvites.check(command.pilotInvite(), command.type(), command.province());
        var application = MerchantApplication.start(
                command.type(),
                command.province(),
                blankToNull(command.workEmail()),
                command.businessTermsAccepted(),
                command.userId(),
                clock.instant());
        applications.insert(application, command.userId());
        syncChecklist(application);
        var invite = command.pilotInvite();
        if (invite != null && !invite.isBlank()) {
            pilotInvites.accept(invite, application.getId(), command.userId(), command.type(), command.province());
        }
        return view(application.getId());
    }

    @Override
    public OnboardingView view(String merchantId) {
        var application = load(merchantId);
        var checks = verifications.listFor(merchantId);
        var docIds = new LinkedHashSet<String>();
        checks.stream()
                .map(Verification::getDocumentId)
                .filter(java.util.Objects::nonNull)
                .forEach(docIds::add);
        application.getLegalDetails().forEach((key, value) -> {
            if (key.endsWith("_doc") && value instanceof String id) {
                docIds.add(id);
            }
        });
        var docs = documents.findAll(merchantId, docIds).stream()
                .collect(Collectors.toMap(Document::id, Function.identity()));
        return new OnboardingView(
                application, checks, docs, places.of(merchantId).zone());
    }

    @Override
    @Transactional
    public OnboardingView update(UpdateAccount.Command command) {
        requireOpen(command.province());
        var application = load(command.merchantId());
        var typeChanged = application.changeAccount(
                command.type(),
                command.province(),
                blankToNull(command.workEmail()),
                command.businessTermsAccepted(),
                clock.instant());
        applications.save(application);
        if (typeChanged) {
            syncChecklist(application);
            storefronts.typeChanged(application.getId(), application.getType());
        }
        return view(application.getId());
    }

    @Override
    @Transactional
    public OnboardingView save(SaveBusiness.Command command) {
        var application = load(command.merchantId());
        var problems = new ArrayList<Violation>();

        DisplayName displayName = collect(problems, () -> new DisplayName(command.displayName()));
        GstNumber gst = null;
        var raw = command.gstNumber();
        if (raw != null && !raw.isBlank()) {
            gst = collect(problems, () -> new GstNumber(raw));
        } else if (!command.structure().gstOptional()) {
            problems.add(new Violation(GstNumber.FIELD, "required", GstNumber.REQUIRED));
        }

        var legal = legalSchema.normalize(command.structure(), command.legalDetails());
        var legalProblems = legalSchema.validate(command.structure(), legal);
        problems.addAll(legalProblems);
        checkLegalDocuments(command.merchantId(), command.structure(), legal, legalProblems, problems);

        var principals = principalsFor(command.structure(), legal, command.principals());
        if (command.structure().listsPrincipals()) {
            problems.addAll(command.structure().check(principals));
        }

        var categories = resolveCategories(application.getType(), command, problems);

        if (legal.get("business_number") instanceof String bn
                && legalProblems.stream().noneMatch(v -> v.field().endsWith(".business_number"))
                && applications.businessNumberTaken(bn, application.getId())) {
            problems.add(new Violation(LegalDetailsSchema.FIELD + ".business_number", "unique", BN_TAKEN));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }

        application.saveBusiness(
                new BusinessDetails(
                        java.util.Objects.requireNonNull(displayName),
                        command.legalName(),
                        command.structure(),
                        gst,
                        legal,
                        principals,
                        categories,
                        command.profile()),
                clock.instant(),
                marketCities(application));
        pilotMarketCity(application.getId()).ifPresent(c -> application.settleCity(c, clock.instant()));
        applications.save(application);
        syncChecklist(application);
        storefronts.ensure(application.getId(), application.getType(), application.getDisplayName());
        return view(application.getId());
    }

    @Override
    @Transactional
    public OnboardingView advance(String merchantId, OnboardingStep step) {
        var application = load(merchantId);
        application.advanceTo(step, clock.instant());
        applications.save(application);
        return view(merchantId);
    }

    @Override
    @Transactional
    public OnboardingView submit(String merchantId, String actorId) {
        var application = load(merchantId);
        if (!application.businessSaved()) {
            throw RuleViolation.of("business", "required", BUSINESS_FIRST);
        }
        var checks = verifications.listFor(merchantId);
        if (checks.isEmpty() || !checks.stream().allMatch(v -> v.getStatus().complete())) {
            throw RuleViolation.of("verifications", "incomplete", CHECKS_FIRST);
        }
        var submitted = application.submit(actorId, clock.instant());
        applications.save(application);
        events.publishEvent(submitted);
        return view(merchantId);
    }

    @Override
    @Transactional
    public OnboardingView approve(String merchantId, String actorId) {
        var application = load(merchantId);
        var now = clock.instant();
        var checks = verifications.listFor(merchantId);
        if (application.getStatus() == MerchantStatus.PENDING
                && kitchenVisitRequired(application)
                && checks.stream()
                        .anyMatch(v ->
                                v.kind() == CheckKind.SITE_VISIT && v.getStatus() != VerificationStatus.VERIFIED)) {
            throw new Conflict("kitchen_visit_required", VISIT_FIRST);
        }
        var approved = application.approve(actorId, now);
        // a business approved without a market would sell nowhere (S-117 found the shop listing none of its offers)
        pilotMarketCity(merchantId)
                .or(() -> defaultMarketCity(application))
                .ifPresent(c -> application.settleCity(c, now));
        applications.save(application);
        checks.forEach(v -> {
            v.confirm(now);
            verifications.save(v);
        });
        events.publishEvent(approved);
        return view(merchantId);
    }

    /** The market cities of the business's province (every market's when it has none): its addresses name one. */
    private List<String> marketCities(MerchantApplication application) {
        var province = application.getProvince();
        return regions.markets().stream()
                .filter(m -> province == null || m.province().equalsIgnoreCase(province.code()))
                .map(MarketProfile::city)
                .toList();
    }

    private java.util.Optional<String> pilotMarketCity(String merchantId) {
        return pilots.byMerchant(merchantId)
                .flatMap(p -> regions.marketById(p.marketId()))
                .map(MarketProfile::city);
    }

    /** The province's first live market, else its first open one (region data, in its sort order). */
    private java.util.Optional<String> defaultMarketCity(MerchantApplication application) {
        var province = application.getProvince();
        if (province == null) {
            return java.util.Optional.empty();
        }
        var markets = regions.markets().stream()
                .filter(m -> m.province().equalsIgnoreCase(province.code()))
                .toList();
        return markets.stream()
                .filter(MarketProfile::live)
                .findFirst()
                .or(() -> markets.stream()
                        .filter(m -> m.status() != LaunchStatus.OFF)
                        .findFirst())
                .map(MarketProfile::city);
    }

    private boolean kitchenVisitRequired(MerchantApplication application) {
        var province = application.getProvince();
        return kitchenVisits.required(
                application.getId(),
                application.getType(),
                province == null ? null : province.code(),
                KitchenVisitService.categoryIds(application));
    }

    /** Keeps the checklist equal to what type + categories require; existing rows keep their evidence. */
    private void syncChecklist(MerchantApplication application) {
        var now = clock.instant();
        var existing = verifications.listFor(application.getId()).stream()
                .collect(Collectors.toMap(Verification::getKey, Function.identity()));
        var specs = CheckSpec.checklist(application.getType(), application.getCategories());
        var next = new ArrayList<Verification>();
        for (int i = 0; i < specs.size(); i++) {
            var spec = specs.get(i);
            var row = existing.get(spec.key());
            if (row == null) {
                row = Verification.open(application.getId(), spec, i, now);
            } else {
                row.moveTo(i);
            }
            next.add(row);
        }
        verifications.replace(application.getId(), next);
        next.stream()
                .filter(v -> v.kind() == CheckKind.KYC)
                .findFirst()
                .ifPresent(kyc -> applications.linkPrincipalKyc(
                        application.getId(),
                        kyc.getId(),
                        application.getStructure() == null
                                ? 0
                                : application.getStructure().kycThresholdPct()));
        // owners may have been added or removed: the kyc row follows their Stripe Identity checks (S-22)
        ownerIdentity.rollup(application.getId());
    }

    private List<Principal> principalsFor(
            BusinessStructure structure, Map<String, Object> legal, List<Principal> principals) {
        if (structure.listsPrincipals()) {
            return principals;
        }
        var owner = legal.get("owner_legal_name") instanceof String name ? name : "";
        return owner.isEmpty()
                ? List.of()
                : List.of(new Principal(owner, PrincipalRole.OWNER, BigDecimal.valueOf(100)));
    }

    private void checkLegalDocuments(
            String merchantId,
            BusinessStructure structure,
            Map<String, Object> legal,
            List<Violation> legalProblems,
            List<Violation> problems) {
        for (var field : legalSchema.documentFields(structure)) {
            var path = LegalDetailsSchema.FIELD + "." + field;
            if (legal.get(field) instanceof String id
                    && legalProblems.stream().noneMatch(v -> v.field().equals(path))
                    && documents.find(merchantId, id).isEmpty()) {
                problems.add(new Violation(path, "required", LegalDetailsSchema.DOC_REQUIRED));
            }
        }
    }

    private List<SelectedCategory> resolveCategories(
            MerchantType type, SaveBusiness.Command command, List<Violation> problems) {
        var ids = new LinkedHashSet<>(command.categoryIds());
        var leaves = taxonomy.leaves(ids);
        var out = new ArrayList<SelectedCategory>();
        var unknown = false;
        for (var id : ids) {
            var leaf = leaves.get(id);
            if (leaf == null || !type.roots().contains(leaf.root())) {
                unknown = true;
                continue;
            }
            out.add(new SelectedCategory(leaf.id(), leaf.name("en"), leaf.root(), leaf.regulator(), false));
        }
        var seen = new HashMap<String, Boolean>();
        for (var raw : command.suggestedCategories()) {
            var name = raw.strip();
            if (name.isEmpty()) {
                continue;
            }
            if (name.length() > SUGGESTION_MAX) {
                problems.add(new Violation(CATEGORIES, "length", "At most %d characters.".formatted(SUGGESTION_MAX)));
                return out;
            }
            var id = SelectedCategory.SUGGESTED_PREFIX + slug(name);
            if (seen.putIfAbsent(id, true) == null) {
                out.add(new SelectedCategory(id, name, null, null, true));
            }
        }
        if (unknown) {
            problems.add(new Violation(CATEGORIES, "unknown", UNKNOWN_CATEGORY));
        } else if (out.isEmpty()) {
            problems.add(new Violation(
                    CATEGORIES, "required", type == MerchantType.SELLER ? PICK_DEPARTMENT : PICK_SERVICE));
        } else if (out.size() > categoryLimits.limit(type)) {
            problems.add(new Violation(CATEGORIES, "range", MAXIMUM.formatted(categoryLimits.limit(type))));
        }
        return out;
    }

    private MerchantApplication load(String merchantId) {
        return applications.findById(merchantId).orElseThrow(() -> new NotFound("merchant", merchantId));
    }

    private static <T> @org.jspecify.annotations.Nullable T collect(
            List<Violation> problems, java.util.function.Supplier<T> build) {
        try {
            return build.get();
        } catch (RuleViolation ex) {
            problems.addAll(ex.getViolations());
            return null;
        }
    }

    private static String slug(String name) {
        var slug = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "-").replaceAll("(^-|-$)", "");
        return slug.isEmpty() ? Integer.toHexString(name.hashCode()) : slug;
    }

    private static @org.jspecify.annotations.Nullable String blankToNull(@org.jspecify.annotations.Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
