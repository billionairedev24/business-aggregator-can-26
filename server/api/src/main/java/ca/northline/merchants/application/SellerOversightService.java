package ca.northline.merchants.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantSearchVisibilityChanged;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.MerchantTierChanged;
import ca.northline.merchants.api.ReverificationRequired;
import ca.northline.merchants.api.SellerDirectory.Oversight;
import ca.northline.merchants.api.SellerSanctions;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
@RequiredArgsConstructor
class SellerOversightService implements SellerOversight, SellerSanctions {

    static final int REASON_MAX = 500;
    private static final Set<String> TIERS = Set.of("registered", "trusted", "master");
    private static final Set<String> SUSPENDABLE = Set.of("active", "paused");

    private final OversightStore store;
    private final AuditTrail audit;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public Oversight suspend(String merchantId, String reason, Actor actor) {
        var why = reason(reason);
        var state = state(merchantId);
        if (state.status() == null || !SUSPENDABLE.contains(state.status())) {
            throw new Conflict("not_active", NOT_ACTIVE);
        }
        var now = clock.instant();
        store.status(merchantId, "suspended", now);
        var action =
                record(merchantId, "suspended", why, Map.of("from", state.status(), "to", "suspended"), actor, now);
        events.publishEvent(new MerchantSuspended(Ids.next(), now, merchantId, actor.userId(), action.id()));
        return action;
    }

    @Override
    public Oversight reinstate(String merchantId, String reason, Actor actor) {
        var why = reason(reason);
        var state = state(merchantId);
        if (!"suspended".equals(state.status())) {
            throw new Conflict("not_suspended", NOT_SUSPENDED);
        }
        var now = clock.instant();
        store.status(merchantId, "active", now);
        var action = record(merchantId, "reinstated", why, Map.of("from", "suspended", "to", "active"), actor, now);
        events.publishEvent(new MerchantReinstated(Ids.next(), now, merchantId, actor.userId(), action.id()));
        return action;
    }

    @Override
    public Oversight requireReverification(String merchantId, String verificationId, String reason, Actor actor) {
        var why = reason(reason);
        state(merchantId);
        var check =
                store.check(merchantId, verificationId).orElseThrow(() -> new NotFound("verification", verificationId));
        if (!Set.of("verified", "submitted").contains(check.status())
                || check.checkType().equals("kyc")) {
            throw new Conflict("not_verifiable", NOT_VERIFIABLE);
        }
        var now = clock.instant();
        store.expire(verificationId, now);
        var action = record(
                merchantId,
                "reverification_required",
                why,
                Map.of("verificationId", verificationId, "checkType", check.checkType()),
                actor,
                now);
        events.publishEvent(new ReverificationRequired(
                Ids.next(), now, merchantId, actor.userId(), action.id(), verificationId, check.checkType()));
        return action;
    }

    @Override
    public Oversight changeTier(String merchantId, String tier, String reason, Actor actor) {
        var why = reason(reason);
        var to = tier.strip();
        if (!TIERS.contains(to)) {
            throw RuleViolation.of("tier", "format", TIER);
        }
        var state = state(merchantId);
        if (state.tier() == null
                || state.status() == null
                || Set.of("applicant", "pending").contains(state.status())) {
            throw new Conflict("not_approved", NOT_APPROVED);
        }
        if (to.equals(state.tier())) {
            throw new Conflict("same_tier", SAME_TIER);
        }
        var now = clock.instant();
        store.tier(merchantId, to, now);
        var action = record(merchantId, "tier_changed", why, Map.of("from", state.tier(), "to", to), actor, now);
        events.publishEvent(
                new MerchantTierChanged(Ids.next(), now, merchantId, actor.userId(), action.id(), state.tier(), to));
        return action;
    }

    @Override
    public Oversight searchVisibility(String merchantId, boolean hidden, String reason, Actor actor) {
        var why = reason(reason);
        state(merchantId);
        var current = store.searchHidden(merchantId);
        if (hidden && current.isPresent()) {
            throw new Conflict("already_hidden", ALREADY_HIDDEN);
        }
        if (!hidden && current.isEmpty()) {
            throw new Conflict("not_hidden", NOT_HIDDEN);
        }
        return visibility(merchantId, hidden, "staff", why, actor);
    }

    private Oversight visibility(String merchantId, boolean hidden, String cause, String reason, Actor actor) {
        var now = clock.instant();
        store.searchHidden(merchantId, hidden ? cause : null, now);
        var action = record(
                merchantId, hidden ? "search_hidden" : "search_restored", reason, Map.of("cause", cause), actor, now);
        events.publishEvent(new MerchantSearchVisibilityChanged(
                Ids.next(), now, merchantId, actor.userId(), action.id(), hidden, cause));
        return action;
    }

    // ── SellerSanctions: the trust rules' automatic consequences (actor "system") ─────────────────────────────

    private static final Actor SYSTEM_ACTOR = new Actor(SellerSanctions.SYSTEM, SellerSanctions.SYSTEM);

    @Override
    public boolean hideFromSearch(String merchantId, String cause, String reason) {
        var state = store.lock(merchantId);
        if (state.isEmpty()
                || !"active".equals(state.get().status())
                || store.searchHidden(merchantId).isPresent()) {
            return false;
        }
        visibility(merchantId, true, cause, reason(reason), SYSTEM_ACTOR);
        return true;
    }

    @Override
    public boolean restoreSearch(String merchantId, String cause, String reason) {
        if (store.lock(merchantId).isEmpty()
                || !store.searchHidden(merchantId).map(cause::equals).orElse(false)) {
            return false;
        }
        visibility(merchantId, false, cause, reason(reason), SYSTEM_ACTOR);
        return true;
    }

    @Override
    public boolean suspend(String merchantId, String reason) {
        var state = store.lock(merchantId);
        if (state.isEmpty()
                || state.get().status() == null
                || !SUSPENDABLE.contains(state.get().status())) {
            return false;
        }
        suspend(merchantId, reason, SYSTEM_ACTOR);
        return true;
    }

    @Override
    @org.springframework.transaction.annotation.Transactional(readOnly = true)
    public List<String> hiddenFromSearch(String cause) {
        return store.hiddenFromSearch(cause);
    }

    private OversightStore.State state(String merchantId) {
        return store.lock(merchantId).orElseThrow(() -> new NotFound("business", merchantId));
    }

    private static String reason(String reason) {
        var why = reason.strip();
        if (why.isEmpty()) {
            throw RuleViolation.of("reason", "required", REASON_REQUIRED);
        }
        if (why.length() > REASON_MAX) {
            throw RuleViolation.of("reason", "length", REASON_LENGTH);
        }
        return why;
    }

    /** The trail row and the platform audit entry (codes only; the reason stays in the business's trail). */
    private Oversight record(
            String merchantId, String action, String reason, Map<String, String> detail, Actor actor, Instant at) {
        var row = new Oversight(Ids.next(), action, reason, detail, actor.userId(), actor.role(), at);
        store.insert(row, merchantId);
        audit.record(new AuditTrail.Entry(
                merchantId,
                actor.userId(),
                actor.role(),
                "merchant." + action,
                "merchant",
                merchantId,
                null,
                Map.of("oversightId", row.id(), "detail", detail)));
        return row;
    }
}
