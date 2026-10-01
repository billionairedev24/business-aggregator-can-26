package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.application.RegistryReviews.DecideReview;
import ca.northline.merchants.application.RegistryReviews.ListReviews;
import ca.northline.merchants.application.RegistryReviews.ReviewView;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.RegistryCheck;
import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryCheck.Trigger;
import ca.northline.merchants.domain.RegistryOutcome;
import ca.northline.merchants.domain.RegistryPlan;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRoutes;
import ca.northline.merchants.domain.RegistrySource;
import ca.northline.merchants.domain.Verification;
import ca.northline.merchants.domain.VerificationStatus;
import ca.northline.region.api.MerchantPlaces;
import ca.northline.region.api.Regions;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Business registry lookups (S-23): runs the {@link RegistryPlan}'s queries through the {@link BusinessRegistry}
 * adapters, stores every answer as evidence, moves the checklist row (all matched → verified with the registry's
 * expiry; otherwise submitted with a manual review for an agent), re-checks verified rows on a schedule and applies
 * the agents' decisions.
 */
@Slf4j
@Service
@Transactional
class RegistryVerificationService implements ListReviews, DecideReview, RecheckRegistries {

    /** Reference of a {@code registry} row when nothing needs registering (a sole proprietor without a trade name). */
    static final String NOT_REQUIRED = "not_required";

    private final Map<RegistrySource, BusinessRegistry> registries = new EnumMap<>(RegistrySource.class);
    private final RegistryCheckStore store;
    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final Clock clock;
    private final MerchantPlaces places;
    private final Regions regions;
    private final AuditTrail audit;
    private final Duration recheckAfter;

    RegistryVerificationService(
            List<BusinessRegistry> adapters,
            RegistryCheckStore store,
            ApplicationRepository applications,
            VerificationRepository verifications,
            Clock clock,
            MerchantPlaces places,
            Regions regions,
            AuditTrail audit,
            @Value("${northline.registries.recheck-after:P30D}") Duration recheckAfter) {
        adapters.forEach(a -> registries.put(a.source(), a));
        this.store = store;
        this.applications = applications;
        this.verifications = verifications;
        this.clock = clock;
        this.places = places;
        this.regions = regions;
        this.audit = audit;
        this.recheckAfter = recheckAfter;
    }

    /** The {@code registry} row: the business record(s) of the application's structure (+ a kitchen's city licence). */
    void business(MerchantApplication application, Verification row, Instant now) {
        run(application, row, RegistryPlan.business(application, routes(application)), Trigger.INITIAL, now);
    }

    /** A licence row ({@code licence:<registry>}, {@code ahs_permit}, {@code aglc}) with the number the owner entered. */
    void licence(MerchantApplication application, Verification row, String registry, String number, Instant now) {
        run(
                application,
                row,
                List.of(RegistryPlan.licence(application, routes(application), registry, number)),
                Trigger.INITIAL,
                now);
    }

    /**
     * The registry adapters of the business's province and city (region model, S-134); a key the region names but no
     * adapter serves is ignored, so its records go to an agent.
     */
    RegistryRoutes routes(MerchantApplication application) {
        var place = places.of(application.getId());
        var province = place.ownProvince() ? place.province() : null;
        var provincial = regions.province(province).stream()
                .flatMap(p -> p.registries().stream())
                .flatMap(key -> source(key).stream())
                .findFirst()
                .orElse(null);
        var municipal = regions.market(place.city(), province).stream()
                .flatMap(m -> m.registries().stream())
                .flatMap(key -> source(key).stream())
                .findFirst()
                .orElse(null);
        var adapter = municipal == null ? null : registries.get(municipal);
        var licences = adapter == null ? java.util.Set.<String>of() : adapter.licences();
        return new RegistryRoutes(provincial, municipal, licences);
    }

    private java.util.Optional<RegistrySource> source(String key) {
        return java.util.Arrays.stream(RegistrySource.values())
                .filter(s -> s.code().equals(key) && registries.containsKey(s))
                .findFirst();
    }

    private void run(
            MerchantApplication application,
            Verification row,
            List<RegistryQuery> queries,
            Trigger trigger,
            Instant now) {
        if (queries.isEmpty()) {
            row.confirmByRegistry(NOT_REQUIRED, null, now);
            store.markRechecked(row.getId(), now);
            return;
        }
        var zone = places.of(application.getId()).zone();
        var checks = queries.stream()
                .map(q -> RegistryCheck.of(
                        Ids.next(), application.getId(), row.getId(), q, answer(q), trigger, now, zone))
                .toList();
        checks.forEach(store::insert);
        var reference = queries.getFirst().number();
        if (checks.stream().allMatch(RegistryCheck::matched)) {
            row.confirmByRegistry(reference, expiry(checks, zone), now);
            store.markRechecked(row.getId(), now);
            return;
        }
        checks.stream()
                .filter(c -> !c.matched())
                .forEach(c -> log.info(
                        "Registry {} {} for {} of {}: {} {} — manual review",
                        c.getSource().code(),
                        c.getQueryNumber(),
                        row.getKey(),
                        application.getId(),
                        c.getOutcome().code(),
                        c.getReasons()));
        if (trigger == Trigger.INITIAL) {
            row.follow(VerificationStatus.SUBMITTED, reference, now);
            return;
        }
        if (checks.stream().allMatch(c -> c.matched() || c.getOutcome() == RegistryOutcome.UNAVAILABLE)) {
            // the source was down: due again tomorrow (not at once, so it doesn't hold up the other rows)
            store.markRechecked(row.getId(), now.minus(recheckAfter).plus(Duration.ofDays(1)));
            return;
        }
        row.lapse(now);
        store.markRechecked(row.getId(), now);
    }

    private Answer answer(RegistryQuery query) {
        if (query.source() == RegistrySource.MANUAL) {
            return new Answer.Manual(null);
        }
        var registry = registries.get(query.source());
        if (registry == null) {
            return new Answer.Unavailable("no adapter for " + query.source().code());
        }
        try {
            return registry.lookup(query);
        } catch (RuntimeException e) {
            log.warn("Registry {} lookup failed: {}", query.source().code(), e.toString());
            return new Answer.Unavailable(e.getClass().getSimpleName());
        }
    }

    /**
     * The earliest registry expiry (municipal licences), as that day in the business's zone (the checklist's "expires
     * on" rule).
     */
    private static @Nullable Instant expiry(List<RegistryCheck> checks, ZoneId zone) {
        return checks.stream()
                .map(RegistryCheck::getRecordExpiresOn)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder())
                .map(day -> onDay(day, zone))
                .orElse(null);
    }

    private static Instant onDay(LocalDate day, ZoneId zone) {
        return day.atStartOfDay(zone).toInstant();
    }

    // ── scheduled re-check ───────────────────────────────────────────────────────────────────────────────────────

    @Override
    public int recheckDue() {
        var now = clock.instant();
        var due = store.dueForRecheck(now.minus(recheckAfter), BATCH);
        for (var d : due) {
            var application = applications.findById(d.merchantId()).orElse(null);
            var row = verifications.find(d.merchantId(), d.verificationId()).orElse(null);
            if (application == null || row == null) {
                continue;
            }
            var queries = row.kind() == CheckKind.REGISTRY
                    ? RegistryPlan.business(application, routes(application))
                    : List.of(RegistryPlan.licence(
                            application,
                            routes(application),
                            Objects.requireNonNullElse(
                                    row.getRegistry(), row.kind().key()),
                            Objects.requireNonNullElse(row.getReference(), "")));
            if (queries.stream().anyMatch(q -> q.number().isBlank())) {
                continue;
            }
            run(application, row, queries, Trigger.RECHECK, now);
            verifications.save(row);
        }
        return due.size();
    }

    // ── console: manual reviews ──────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<ReviewView> open(int limit) {
        return store.openReviews(limit).stream().map(this::view).toList();
    }

    @Override
    public ReviewView decide(Command command) {
        var check = store.lock(command.checkId()).orElseThrow(() -> new NotFound("registry review", command.checkId()));
        var now = clock.instant();
        check.decide(command.approve(), command.agentId(), command.note(), now);
        store.saveReview(check);
        var row = verifications
                .find(check.getMerchantId(), check.getVerificationId())
                .orElseThrow(() -> new NotFound("verification", check.getVerificationId()));
        var reference = command.reference() == null || command.reference().isBlank()
                ? check.getQueryNumber()
                : command.reference().strip();
        if (!command.approve()) {
            row.follow(VerificationStatus.REJECTED, reference, now);
        } else if (!store.hasOpenReview(row.getId())) {
            var expires = command.expiresOn() != null ? command.expiresOn() : check.getRecordExpiresOn();
            var zone = places.of(check.getMerchantId()).zone();
            row.confirmByRegistry(reference, expires == null ? null : onDay(expires, zone), now);
            store.markRechecked(row.getId(), now);
        }
        verifications.save(row);
        audit.record(AuditTrail.Entry.of(
                        check.getMerchantId(),
                        command.agentId(),
                        command.role(),
                        command.approve() ? "verification.registry_approved" : "verification.registry_rejected",
                        "registry_check",
                        check.getId())
                .withChange(Map.of("review", "open"), Map.of("review", command.approve() ? "approved" : "rejected")));
        return view(check);
    }

    private ReviewView view(RegistryCheck c) {
        var name = applications
                .findById(c.getMerchantId())
                .map(MerchantApplication::getDisplayName)
                .orElse("");
        var key = verifications
                .find(c.getMerchantId(), c.getVerificationId())
                .map(Verification::getKey)
                .orElse("");
        return ReviewView.of(c, name, key);
    }
}
