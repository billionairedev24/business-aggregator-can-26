package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.identity.api.StaffDirectory;
import ca.northline.merchants.api.ApplicationDecided;
import ca.northline.merchants.api.ApplicationReviewed;
import ca.northline.merchants.application.ManageApplication.ApproveApplication;
import ca.northline.merchants.application.RegistryReviews.ReviewView;
import ca.northline.merchants.application.VerificationQueue.ApplicationDetail;
import ca.northline.merchants.application.VerificationQueue.ApplicationRow;
import ca.northline.merchants.application.VerificationQueue.CheckRow;
import ca.northline.merchants.application.VerificationQueue.CheckState;
import ca.northline.merchants.application.VerificationQueue.DecideApplication;
import ca.northline.merchants.application.VerificationQueue.DecideIdentityReview;
import ca.northline.merchants.application.VerificationQueue.Decision;
import ca.northline.merchants.application.VerificationQueue.DecisionRow;
import ca.northline.merchants.application.VerificationQueue.ListApplications;
import ca.northline.merchants.application.VerificationQueue.OwnerReview;
import ca.northline.merchants.application.VerificationQueue.Queue;
import ca.northline.merchants.application.VerificationQueue.Recommendation;
import ca.northline.merchants.application.VerificationQueue.ReviewApplication;
import ca.northline.merchants.application.VerificationQueue.ReviewRow;
import ca.northline.merchants.application.VerificationQueue.Risk;
import ca.northline.merchants.application.VerificationQueue.Stage;
import ca.northline.merchants.application.VerificationQueue.ViewApplication;
import ca.northline.merchants.application.VerificationQueueStore.Application;
import ca.northline.merchants.application.VerificationQueueStore.Review;
import ca.northline.merchants.domain.CheckKind;
import ca.northline.merchants.domain.CheckType;
import ca.northline.merchants.domain.IdentityCheckStatus;
import ca.northline.merchants.domain.MerchantApplication;
import ca.northline.merchants.domain.MerchantStatus;
import ca.northline.merchants.domain.MerchantTier;
import ca.northline.merchants.domain.Verification;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.MerchantScope;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.security.StaffAccessDenied;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The console's verification queue (S-79): reads applications with their checks, and records the agent's decisions —
 * each in one transaction with its audit entry ({@code developer.audit_log}) and the {@link ApplicationDecided} event
 * that emails the owners after commit. Two-person review (2026-10-18): a reviewer's recommendation first ({@link
 * ReviewApplication}), then the decision by someone who didn't review the open round ({@link FourEyesPolicy}).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class VerificationQueueService
        implements ListApplications, ViewApplication, ReviewApplication, DecideApplication, DecideIdentityReview {

    static final int LIMIT = 200;
    static final Duration MEDIAN_WINDOW = Duration.ofDays(30);

    private final VerificationQueueStore store;
    private final ApplicationRepository applications;
    private final VerificationRepository verifications;
    private final OwnerIdentityStore owners;
    private final OwnerIdentityService ownerIdentity;
    private final RegistryCheckStore registry;
    private final ApproveApplication approve;
    private final Taxonomy taxonomy;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final FourEyesPolicy fourEyes;
    private final StaffDirectory staff;
    private final Clock clock;

    @Override
    public Queue list(MerchantScope scope, String lang) {
        var now = clock.instant();
        var rows = rows(store.applications(scope, now.minus(Duration.ofDays(RECENT_DAYS)), LIMIT), lang);
        var pending = rows.stream()
                .filter(r -> MerchantStatus.PENDING.code().equals(r.status()))
                .count();
        return new Queue(rows, pending, store.medianDecisionHours(scope, now.minus(MEDIAN_WINDOW)), fourEyes.enabled());
    }

    @Override
    public ApplicationDetail view(String merchantId, String lang) {
        var application = store.application(merchantId).orElseThrow(() -> new NotFound("application", merchantId));
        var row = rows(List.of(application), lang).getFirst();
        var businessName = application.displayName();
        var keys = verifications.listFor(merchantId).stream()
                .collect(java.util.stream.Collectors.toMap(Verification::getId, Verification::getKey));
        var reviews = registry.reviewsOf(merchantId).stream()
                .map(c -> ReviewView.of(c, businessName, keys.getOrDefault(c.getVerificationId(), "")))
                .toList();
        var people = owners.owners(merchantId).stream()
                .map(o -> {
                    var c = o.check();
                    return new OwnerReview(
                            c == null ? null : c.getId(),
                            o.legalName(),
                            o.role().code(),
                            o.ownershipPct(),
                            c == null ? "not_started" : c.getStatus().code(),
                            c == null || c.getNameMatch() == null
                                    ? null
                                    : c.getNameMatch().code(),
                            c == null || c.getDobMatch() == null
                                    ? null
                                    : c.getDobMatch().code(),
                            c == null ? null : c.getLastError(),
                            c == null ? 0 : c.getAttempts(),
                            c == null ? null : c.getReviewNote(),
                            c == null ? null : c.getReviewedAt());
                })
                .toList();
        var names = new java.util.HashMap<String, String>();
        var history =
                store.reviews(merchantId).stream().map(r -> reviewRow(r, names)).toList();
        return new ApplicationDetail(row, people, reviews, history, store.decisions(merchantId), fourEyes.enabled());
    }

    @Override
    @Transactional
    public ApplicationDetail review(ReviewApplication.Command command, String lang) {
        var application = pending(command.merchantId());
        var checklist = verifications.listFor(command.merchantId());
        var note = blankToNull(command.note());
        List<String> keys = List.of();
        switch (command.recommendation()) {
            case APPROVE -> {
                if (hasOpenReviews(command.merchantId(), checklist)) {
                    throw new Conflict("reviews_open", "Decide the open registry and identity reviews first.");
                }
            }
            case REJECT -> {
                if (note == null) {
                    throw RuleViolation.of("note", "required", VerificationQueue.REJECT_REASON_REQUIRED);
                }
            }
            case REQUEST_INFO -> {
                keys = List.copyOf(checkKeys(command.checkKeys(), checklist));
                if (note == null) {
                    throw RuleViolation.of("note", "required", VerificationQueue.NOTE_REQUIRED);
                }
            }
        }
        var now = clock.instant();
        var review = new Review(
                Ids.next(),
                command.merchantId(),
                command.recommendation(),
                keys,
                note,
                command.reviewerId(),
                now,
                null);
        store.insertReview(review, command.role(), application.getSubmittedAt());
        var after = new LinkedHashMap<String, Object>();
        after.put("stage", Stage.DECISION.code());
        after.put("reviewId", review.id());
        after.put("recommendation", review.recommendation().code());
        after.put("checks", keys);
        if (note != null) {
            after.put("note", note);
        }
        audit.record(AuditTrail.Entry.of(
                        command.merchantId(),
                        command.reviewerId(),
                        command.role(),
                        "verification.application_reviewed",
                        "merchant",
                        command.merchantId())
                .withChange(Map.of("status", MerchantStatus.PENDING.code()), after));
        events.publishEvent(new ApplicationReviewed(
                Ids.next(),
                now,
                command.merchantId(),
                command.reviewerId(),
                review.id(),
                review.recommendation().code()));
        return view(command.merchantId(), lang);
    }

    @Override
    @Transactional
    public ApplicationDetail decide(DecideApplication.Command command, String lang) {
        var application = pending(command.merchantId());
        var round = store.reviews(command.merchantId()).stream()
                .filter(Review::open)
                .toList();
        var current = round.isEmpty() ? null : round.getFirst();
        if (fourEyes.enabled()) {
            if (current == null) {
                throw new Conflict("review_required", VerificationQueue.REVIEW_REQUIRED);
            }
            if (round.stream().anyMatch(r -> r.reviewedBy().equals(command.agentId()))) {
                throw new StaffAccessDenied(StaffAccessDenied.Reason.SAME_PERSON, VerificationQueue.SAME_PERSON);
            }
        }
        var reviewId = current == null ? null : current.id();
        var now = clock.instant();
        var submittedAt = application.getSubmittedAt();
        var checklist = verifications.listFor(command.merchantId());
        DecisionRow decision;
        if (command.decision() == Decision.APPROVED) {
            if (hasOpenReviews(command.merchantId(), checklist)) {
                throw new Conflict("reviews_open", "Decide the open registry and identity reviews first.");
            }
            if (current != null
                    && current.recommendation() != Recommendation.APPROVE
                    && blankToNull(command.note()) == null) {
                throw RuleViolation.of("note", "required", VerificationQueue.OVERRIDE_REASON_REQUIRED);
            }
            approve.approve(command.merchantId(), command.agentId());
            decision = new DecisionRow(
                    Ids.next(),
                    Decision.APPROVED,
                    List.of(),
                    blankToNull(command.note()),
                    command.agentId(),
                    now,
                    reviewId);
            audit.record(AuditTrail.Entry.of(
                            command.merchantId(),
                            command.agentId(),
                            command.role(),
                            "verification.application_approved",
                            "merchant",
                            command.merchantId())
                    .withChange(
                            Map.of("status", MerchantStatus.PENDING.code()),
                            decisionAfter(
                                    Map.of("status", MerchantStatus.ACTIVE.code(), "tier", tier(application)),
                                    current,
                                    blankToNull(command.note()))));
        } else {
            var keys = checkKeys(command.checkKeys(), checklist);
            var note = blankToNull(command.note());
            if (note == null) {
                throw RuleViolation.of("note", "required", VerificationQueue.NOTE_REQUIRED);
            }
            application.returnForInformation(now);
            applications.save(application);
            for (var row : checklist) {
                if (keys.contains(row.getKey())) {
                    row.reopen(now);
                    verifications.save(row);
                    if (row.kind() == CheckKind.KYC) {
                        sendOwnersBack(command.merchantId(), command.agentId(), now);
                    }
                }
            }
            decision = new DecisionRow(
                    Ids.next(), Decision.INFO_REQUESTED, List.copyOf(keys), note, command.agentId(), now, reviewId);
            audit.record(AuditTrail.Entry.of(
                            command.merchantId(),
                            command.agentId(),
                            command.role(),
                            "verification.info_requested",
                            "merchant",
                            command.merchantId())
                    .withChange(
                            Map.of("status", MerchantStatus.PENDING.code()),
                            decisionAfter(
                                    Map.of("status", MerchantStatus.APPLICANT.code(), "checks", List.copyOf(keys)),
                                    current,
                                    note)));
        }
        store.insert(command.merchantId(), decision, command.role(), submittedAt);
        // the round is over: a re-submitted application starts again at stage 1
        store.closeRound(command.merchantId(), decision.id());
        events.publishEvent(new ApplicationDecided(
                Ids.next(),
                now,
                command.merchantId(),
                command.agentId(),
                decision.decision().code(),
                decision.checkKeys(),
                decision.note(),
                current == null ? null : current.reviewedBy()));
        return view(command.merchantId(), lang);
    }

    @Override
    @Transactional
    public ApplicationDetail decide(DecideIdentityReview.Command command, String lang) {
        var check = owners.find(command.merchantId(), command.checkId())
                .orElseThrow(() -> new NotFound("identity review", command.checkId()));
        var before = check.getStatus().code();
        check.decideReview(command.approve(), command.agentId(), blankToNull(command.note()), clock.instant());
        owners.save(check);
        ownerIdentity.rollup(command.merchantId());
        audit.record(AuditTrail.Entry.of(
                        command.merchantId(),
                        command.agentId(),
                        command.role(),
                        command.approve() ? "verification.identity_approved" : "verification.identity_rejected",
                        "owner_identity_check",
                        check.getId())
                .withChange(
                        Map.of("status", before),
                        Map.of("status", check.getStatus().code())));
        return view(command.merchantId(), lang);
    }

    private List<ApplicationRow> rows(List<Application> found, String lang) {
        var ids = found.stream().map(Application::merchantId).toList();
        var inReview = store.verificationsInReview(ids);
        var identityReview = store.identityInReview(ids);
        var current = store.currentReviews(ids);
        var names = new java.util.HashMap<String, String>();
        var leaves = taxonomy.leaves(
                found.stream().flatMap(a -> a.categoryIds().stream()).distinct().toList());
        return found.stream()
                .map(a -> {
                    var checks = verifications.listFor(a.merchantId()).stream()
                            .map(v -> check(v, inReview, identityReview.contains(a.merchantId())))
                            .toList();
                    var pending = MerchantStatus.PENDING.code().equals(a.status());
                    var categories = a.categoryIds().stream()
                            .map(id -> leaves.containsKey(id) ? leaves.get(id).name(lang) : id)
                            .toList();
                    return new ApplicationRow(
                            a.merchantId(),
                            a.displayName(),
                            a.legalName(),
                            a.type(),
                            a.structure(),
                            categories,
                            a.province(),
                            a.city(),
                            a.status(),
                            a.submittedAt(),
                            checks,
                            risk(checks),
                            a.decision(),
                            a.decidedAt(),
                            pending ? (current.containsKey(a.merchantId()) ? Stage.DECISION : Stage.REVIEW) : null,
                            pending && current.containsKey(a.merchantId())
                                    ? reviewRow(current.get(a.merchantId()), names)
                                    : null);
                })
                .toList();
    }

    private static CheckRow check(Verification v, Set<String> inReview, boolean identityReview) {
        var state = switch (v.getStatus()) {
            case VERIFIED -> CheckState.PASSED;
            case SUBMITTED ->
                inReview.contains(v.getId()) || (identityReview && v.getType() == CheckType.KYC)
                        ? CheckState.REVIEW
                        : CheckState.WAITING;
            case TODO, EXPIRED, REJECTED -> CheckState.FAILED;
        };
        return new CheckRow(
                v.getId(),
                v.getKey(),
                v.getType().code(),
                v.getRegistry(),
                v.getStatus().code(),
                state,
                v.getReference(),
                v.getExpiresAt());
    }

    static Risk risk(List<CheckRow> checks) {
        var licences = Set.of(CheckType.LICENCE.code(), CheckType.AHS_PERMIT.code());
        if (checks.stream().anyMatch(c -> licences.contains(c.type()) && c.state() != CheckState.PASSED)) {
            return Risk.HIGH;
        }
        return checks.stream().anyMatch(c -> c.state() == CheckState.REVIEW || c.state() == CheckState.FAILED)
                ? Risk.MEDIUM
                : Risk.LOW;
    }

    private boolean hasOpenReviews(String merchantId, List<Verification> checklist) {
        var open = store.verificationsInReview(List.of(merchantId));
        return checklist.stream().anyMatch(v -> open.contains(v.getId()))
                || !store.identityInReview(List.of(merchantId)).isEmpty();
    }

    private MerchantApplication pending(String merchantId) {
        var application = applications.findById(merchantId).orElseThrow(() -> new NotFound("application", merchantId));
        if (application.getStatus() != MerchantStatus.PENDING) {
            throw new Conflict("not_pending", "This application isn't waiting for a decision.");
        }
        return application;
    }

    /** The decision's audit snapshot: what changed, plus the review it followed and the approver's note. */
    private static Map<String, Object> decisionAfter(
            Map<String, ?> change, @Nullable Review review, @Nullable String note) {
        var after = new LinkedHashMap<String, Object>(change);
        if (review != null) {
            after.put("reviewId", review.id());
            after.put("reviewedBy", review.reviewedBy());
            after.put("recommendation", review.recommendation().code());
        }
        if (note != null) {
            after.put("note", note);
        }
        return after;
    }

    private ReviewRow reviewRow(Review r, Map<String, String> names) {
        var name = names.computeIfAbsent(
                r.reviewedBy(),
                id -> staff.member(id).map(StaffDirectory.Member::name).orElse(id));
        return new ReviewRow(
                r.id(),
                r.recommendation(),
                r.checkKeys(),
                r.note(),
                r.reviewedBy(),
                name,
                r.reviewedAt(),
                r.decisionId());
    }

    private static Collection<String> checkKeys(List<String> requested, List<Verification> checklist) {
        if (requested.isEmpty()) {
            throw RuleViolation.of("checkKeys", "required", VerificationQueue.CHECKS_REQUIRED);
        }
        var known = checklist.stream().map(Verification::getKey).toList();
        var keys = new LinkedHashSet<String>();
        for (var key : requested) {
            if (!known.contains(key)) {
                throw RuleViolation.of("checkKeys", "option", VerificationQueue.UNKNOWN_CHECK);
            }
            keys.add(key);
        }
        return keys;
    }

    /** Identity on "Request info": every owner who handed in a verification does it again. */
    private void sendOwnersBack(String merchantId, String agentId, Instant now) {
        for (var owner : owners.owners(merchantId)) {
            var check = owner.check();
            if (check != null && check.getStatus() != IdentityCheckStatus.RETRY && check.sendBack(agentId, now)) {
                owners.save(check);
            }
        }
    }

    private static String tier(MerchantApplication application) {
        return application.getTier() == null
                ? MerchantTier.REGISTERED.code()
                : application.getTier().code();
    }

    private static @Nullable String blankToNull(@Nullable String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
