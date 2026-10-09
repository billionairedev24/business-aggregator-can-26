package ca.northline.promotions.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.BusinessNames;
import ca.northline.promotions.api.Promotions;
import ca.northline.promotions.application.PromotionStore.Redemption;
import ca.northline.promotions.domain.Allocation;
import ca.northline.promotions.domain.PromoCode;
import ca.northline.promotions.domain.PromoMessages;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.trust.api.PointsWallet;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link Promotions} and the console's {@link ManagePromoCodes}. Pricing is pure arithmetic over the basket
 * ({@link PromoCode#discount}, {@link Allocation}); reserving counts the code's uses under a lock on the code row and
 * the customer's points under a per-customer lock, so two checkouts can't spend the same last use or the same points.
 */
@Service
@RequiredArgsConstructor
@Transactional
class PromotionService implements Promotions, ManagePromoCodes {

    /** Every line keeps at least this much for the card (Stripe's smallest charge is $0.50). */
    static final long LINE_FLOOR_CENTS = 100;

    private final PromotionStore store;
    private final PointsWallet wallet;
    private final BusinessNames businesses;
    private final AuditTrail audit;
    private final Clock clock;

    // ── checkout ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Priced price(Basket basket, Ask ask) {
        return priced(basket, ask, "", "", false);
    }

    @Override
    public Priced reserve(String refId, Instant until, Basket basket, Ask ask) {
        var priced = priced(basket, ask, basket.kind(), refId, true);
        store.delete(basket.kind(), refId);
        if (priced.code() == null && priced.points() == 0) {
            return priced;
        }
        var code = priced.code() == null ? null : store.byCode(priced.code()).orElseThrow();
        store.save(
                new Redemption(
                        Ids.next(),
                        basket.customerId(),
                        basket.kind(),
                        refId,
                        code == null ? null : code.id(),
                        priced.code(),
                        priced.fundedBy(),
                        priced.discountCents(),
                        priced.points(),
                        priced.pointsCents(),
                        "reserved",
                        until,
                        priced.lines()),
                clock.instant());
        return priced;
    }

    @Override
    @Transactional(readOnly = true)
    public Priced reserved(String kind, String refId) {
        return store.redemption(kind, refId)
                .filter(r -> !"released".equals(r.state()))
                .map(r -> new Priced(r.code(), r.discountCents(), r.fundedBy(), r.points(), r.pointsCents(), 0, r.lines()))
                .orElseGet(() -> new Priced(null, 0, null, 0, 0, 0, List.of()));
    }

    @Override
    public void redeem(String kind, String refId) {
        var r = store.redemption(kind, refId).orElse(null);
        if (r == null || !"reserved".equals(r.state())) {
            return;
        }
        if (r.points() > 0) {
            store.lockCustomer(r.customerId());
            var held = store.pointsHeld(r.customerId(), clock.instant(), kind, refId);
            // the reservation lapsed and other checkouts took the points meanwhile: don't overdraw the wallet
            if (wallet.balance(r.customerId()) - held < r.points()) {
                throw new Conflict("points_gone", "Your points were used elsewhere. Start the checkout again.");
            }
            wallet.redeem(r.customerId(), r.points(), r.id(), note(r));
        }
        store.redeemed(r.id(), clock.instant());
    }

    @Override
    public void release(String kind, String refId) {
        store.redemption(kind, refId).ifPresent(r -> {
            if ("redeemed".equals(r.state()) && r.points() > 0) {
                wallet.giveBack(r.customerId(), r.points(), "redemption_return", r.id(), note(r));
            }
            if (!"released".equals(r.state())) {
                store.released(r.id(), clock.instant());
            }
        });
    }

    private static String note(Redemption r) {
        return switch (r.kind()) {
            case "service" -> "Booking";
            case "food" -> "Food order";
            default -> "Order";
        };
    }

    /**
     * @param reserving count other checkouts' holds and lock (reserve); a quote only checks
     */
    private Priced priced(Basket basket, Ask ask, String kind, String refId, boolean reserving) {
        var now = clock.instant();
        var items = basket.items();
        var raw = ask.code();
        var codeText = raw == null || raw.isBlank() ? null : PromoMessages.normalise(raw);
        var discounts = new ArrayList<Long>();
        items.forEach(_ -> discounts.add(0L));
        PromoCode code = null;
        if (codeText != null) {
            code = (reserving ? store.lockByCode(codeText) : store.byCode(codeText))
                    .orElseThrow(() -> PromoCode.violation(PromoMessages.UNKNOWN));
            code.checkUsable(basket.kind(), now);
            var limit = code.totalLimit();
            if (limit != null && store.uses(code.id(), null, now, kind, refId) >= limit) {
                throw PromoCode.violation(PromoMessages.USED_UP);
            }
            if (store.uses(code.id(), basket.customerId(), now, kind, refId) >= code.perCustomerLimit()) {
                throw PromoCode.violation(PromoMessages.USED);
            }
            var c = code;
            var weights = items.stream()
                    .map(i -> c.covers(i.merchantId()) ? i.amountCents() : 0L)
                    .toList();
            var eligible = weights.stream().mapToLong(Long::longValue).sum();
            var off = code.discount(eligible);
            var caps = items.stream()
                    .map(i -> c.covers(i.merchantId()) ? Math.max(0, i.amountCents() - LINE_FLOOR_CENTS) : 0L)
                    .toList();
            var spread = Allocation.spread(off, weights, caps);
            if (spread.stream().mapToLong(Long::longValue).sum() == 0) {
                throw PromoCode.violation(PromoMessages.NOT_HERE);
            }
            for (int i = 0; i < items.size(); i++) {
                discounts.set(i, spread.get(i));
            }
        }
        // points
        var settings = wallet.settings();
        if (reserving && ask.usePoints()) {
            store.lockCustomer(basket.customerId());
        }
        var available = Math.max(
                0, wallet.balance(basket.customerId()) - store.pointsHeld(basket.customerId(), now, kind, refId));
        var pointsCents = 0L;
        var points = 0L;
        var pointShares = new ArrayList<Long>();
        items.forEach(_ -> pointShares.add(0L));
        if (ask.usePoints() && available >= settings.minPoints()) {
            var caps = new ArrayList<Long>();
            var weights = new ArrayList<Long>();
            for (int i = 0; i < items.size(); i++) {
                var taxable = items.get(i).amountCents() - discounts.get(i);
                weights.add(taxable);
                caps.add(taxable * settings.maxOrderPercent() / 100);
            }
            var wanted = Math.min(settings.centsOf(available), caps.stream().mapToLong(Long::longValue).sum());
            var spread = Allocation.spread(wanted, weights, caps);
            pointsCents = spread.stream().mapToLong(Long::longValue).sum();
            points = Math.min(available, settings.pointsFor(pointsCents));
            if (points < settings.minPoints()) {
                points = 0;
                pointsCents = 0;
            } else {
                for (int i = 0; i < items.size(); i++) {
                    pointShares.set(i, spread.get(i));
                }
            }
        }
        var funder = code == null ? null : code.fundedBy();
        var lines = new ArrayList<Line>();
        for (int i = 0; i < items.size(); i++) {
            var it = items.get(i);
            lines.add(new Line(
                    it.escrowRefType(),
                    it.escrowRefId(),
                    it.merchantId(),
                    it.amountCents(),
                    discounts.get(i),
                    discounts.get(i) > 0 ? funder : null,
                    pointShares.get(i)));
        }
        var discount = discounts.stream().mapToLong(Long::longValue).sum();
        return new Priced(
                code == null ? null : code.code(), discount, funder, points, pointsCents, available, lines);
    }

    // ── console ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public List<CodeView> list() {
        return store.all(500).stream().map(this::view).toList();
    }

    @Override
    public CodeView create(NewCode n, String staffId, String role) {
        var code = PromoMessages.normalise(n.code());
        var problems = PromoCode.problems(
                code,
                n.kind(),
                n.percent(),
                n.amountCents(),
                n.maxDiscountCents(),
                n.minSpendCents(),
                n.startsAt(),
                n.endsAt(),
                n.perCustomerLimit(),
                n.totalLimit(),
                n.fundedBy(),
                n.merchantId(),
                n.appliesTo(),
                n.description());
        if (problems.isEmpty()
                && PromoCode.MERCHANT.equals(n.fundedBy())
                && businesses.displayName(Objects.requireNonNull(n.merchantId())).isEmpty()) {
            problems = List.of(new RuleViolation.Violation("merchantId", "required", PromoMessages.MERCHANT));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        var percent = "percent".equals(n.kind());
        var promo = new PromoCode(
                Ids.next(),
                code,
                n.description() == null || n.description().isBlank() ? null : n.description().strip(),
                Objects.requireNonNull(n.kind()),
                percent ? n.percent() : null,
                percent ? null : n.amountCents(),
                percent ? n.maxDiscountCents() : null,
                Objects.requireNonNullElse(n.minSpendCents(), 0L),
                Objects.requireNonNull(n.startsAt()),
                Objects.requireNonNull(n.endsAt()),
                Objects.requireNonNullElse(n.perCustomerLimit(), 1),
                n.totalLimit(),
                Objects.requireNonNull(n.fundedBy()),
                PromoCode.MERCHANT.equals(n.fundedBy()) ? n.merchantId() : null,
                new HashSet<>(n.appliesTo()),
                true);
        if (!store.insert(promo, staffId, clock.instant())) {
            throw new Conflict("code_taken", PromoMessages.CODE_TAKEN);
        }
        audit.record(new AuditTrail.Entry(
                promo.merchantId(),
                staffId,
                role,
                "promotions.code_created",
                "promo_code",
                promo.id(),
                null,
                Map.of(
                        "code", promo.code(),
                        "kind", promo.kind(),
                        "fundedBy", promo.fundedBy(),
                        "value", String.valueOf(percent ? promo.percent() : promo.amountCents()))));
        return view(promo);
    }

    @Override
    public CodeView setActive(String id, boolean active, String staffId, String role) {
        var promo = store.byId(id).orElseThrow(() -> new NotFound("promo_code", id));
        if (store.setActive(id, active, clock.instant())) {
            audit.record(new AuditTrail.Entry(
                    promo.merchantId(),
                    staffId,
                    role,
                    active ? "promotions.code_on" : "promotions.code_off",
                    "promo_code",
                    id,
                    Map.of("active", String.valueOf(promo.active())),
                    Map.of("active", String.valueOf(active))));
        }
        return view(store.byId(id).orElseThrow());
    }

    private CodeView view(PromoCode c) {
        var now = clock.instant();
        var usage = store.usage(c.id());
        String state;
        if (!c.active()) {
            state = "off";
        } else if (now.isBefore(c.startsAt())) {
            state = "scheduled";
        } else if (!now.isBefore(c.endsAt())) {
            state = "ended";
        } else {
            state = "live";
        }
        @Nullable String merchantName = c.merchantId() == null
                ? null
                : businesses.displayName(c.merchantId()).orElse(null);
        return new CodeView(
                c.id(),
                c.code(),
                c.description(),
                c.kind(),
                c.percent(),
                c.amountCents(),
                c.maxDiscountCents(),
                c.minSpendCents(),
                c.startsAt(),
                c.endsAt(),
                c.perCustomerLimit(),
                c.totalLimit(),
                c.fundedBy(),
                c.merchantId(),
                merchantName,
                c.appliesTo().stream().sorted().toList(),
                c.active(),
                state,
                usage.redeemed(),
                usage.discountCents());
    }
}
