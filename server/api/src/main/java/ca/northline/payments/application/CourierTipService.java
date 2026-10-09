package ca.northline.payments.application;

import ca.northline.developer.api.AuditTrail;
import ca.northline.payments.api.CourierTips;
import ca.northline.payments.api.PaymentAuthorizations;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.application.TipStore.StoredTip;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link CourierTips}. A checkout tip is charged with its order's PaymentIntent (the escrow capture or the delivery
 * fee's, which call {@link #captured}); a tip after delivery has its own PaymentIntent ({@code courier_tip}, captured as
 * soon as the card authorizes it). Either way the ledger moves it to {@code courier:<user id>} once it is both charged
 * and its courier known.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class CourierTipService implements CourierTips {

    static final Set<String> REASONS = Set.of("not_delivered", "duplicate", "amount_error");

    private final TipStore tips;
    private final LedgerRepository ledger;
    private final PaymentAuthorizations authorizations;
    private final PaymentGateway gateway;
    private final EscrowRepository intents;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    public void atCheckout(String orderId, String customerId, long cents, String stripePaymentIntent) {
        if (cents <= 0) {
            return;
        }
        tips.insert(new StoredTip(
                Ids.next(),
                orderId,
                customerId,
                null,
                cents,
                "checkout",
                stripePaymentIntent,
                "pending",
                clock.instant(),
                null));
    }

    /** The order's PaymentIntent carrying its checkout tip was captured (escrow or delivery fee). */
    void captured(String orderId, Instant at) {
        tips.checkoutTip(orderId).filter(t -> "pending".equals(t.state())).ifPresent(t -> {
            tips.captured(t.id(), at);
            if (t.courierUserId() != null) {
                allocateNow(t, t.courierUserId(), at);
            }
        });
    }

    /** The checkout tip that will be captured with this order's charge (0 when none). */
    long checkoutTipCents(String orderId) {
        return tips.checkoutTip(orderId)
                .filter(t -> "pending".equals(t.state()))
                .map(StoredTip::amountCents)
                .orElse(0L);
    }

    @Override
    public void allocate(String orderId, String courierUserId, Instant at) {
        tips.checkoutTip(orderId).ifPresent(t -> {
            if (t.courierUserId() == null) {
                tips.courier(t.id(), courierUserId);
            }
            if ("captured".equals(t.state())) {
                allocateNow(t, courierUserId, at);
            }
        });
    }

    private void allocateNow(StoredTip t, String courierUserId, Instant at) {
        ledger.post(LedgerEntry.tipAllocated(t.id(), courierUserId, t.amountCents(), at));
        tips.allocated(t.id(), at);
    }

    @Override
    public Tip startAfterDelivery(
            String orderId, String customerId, String courierUserId, long cents, @Nullable String clientKey) {
        if (cents <= 0 || cents > MAX_CENTS) {
            throw RuleViolation.of("amountCents", "range", TOO_MUCH);
        }
        for (var t : tips.ofOrder(orderId)) {
            if (!"after_delivery".equals(t.source())) {
                continue;
            }
            switch (t.state()) {
                case "pending" -> {
                    cancelQuietly(t);
                    tips.canceled(t.id());
                }
                case "canceled" -> {}
                default -> throw new Conflict("already_tipped", ALREADY);
            }
        }
        var id = Ids.next();
        var started = authorizations.start(new PaymentAuthorizations.Request(
                PaymentAuthorizations.PLATFORM,
                LedgerEntry.TIP,
                id,
                customerId,
                cents,
                0,
                "order:" + orderId,
                clientKey));
        var tip = new StoredTip(
                id,
                orderId,
                customerId,
                courierUserId,
                cents,
                "after_delivery",
                started.paymentIntent(),
                "pending",
                clock.instant(),
                null);
        if (!tips.insert(tip)) {
            cancelQuietly(tip);
            throw new Conflict("already_tipped", ALREADY);
        }
        return view(tip, started.clientSecret());
    }

    @Override
    public Tip confirm(String customerId, String tipId) {
        var t = tips.lock(tipId)
                .filter(x -> x.customerId().equals(customerId) && "after_delivery".equals(x.source()))
                .orElseThrow(() -> new NotFound("tip", tipId));
        if (!"pending".equals(t.state())) {
            return view(t, null);
        }
        var pi = java.util.Objects.requireNonNull(t.stripePaymentIntent());
        var authorization = gateway.authorization(pi);
        if (authorization.status() != IntentStatus.AUTHORIZED) {
            throw new Conflict("payment_not_authorized", "The card payment isn't authorized yet.");
        }
        var charge = gateway.capture(pi, t.amountCents(), StripeIdempotencyKeys.of("capture-tip", tipId));
        intents.intentByStripeId(pi).ifPresent(i -> intents.recordCapture(i.id(), charge));
        var now = clock.instant();
        var courier = java.util.Objects.requireNonNull(t.courierUserId());
        ledger.post(LedgerEntry.tipCaptured(tipId, courier, t.amountCents(), now));
        tips.captured(tipId, now);
        tips.allocated(tipId, now);
        return view(tips.find(tipId).orElseThrow(), null);
    }

    @Override
    @Transactional(readOnly = true)
    public List<Tip> ofOrder(String orderId) {
        return tips.ofOrder(orderId).stream().map(t -> view(t, null)).toList();
    }

    @Override
    public Tip refund(String tipId, String reason, String staffId, String role) {
        if (!REASONS.contains(reason)) {
            throw RuleViolation.of("reason", "required", REASON);
        }
        var t = tips.lock(tipId).orElseThrow(() -> new NotFound("tip", tipId));
        switch (t.state()) {
            case "refunded" -> throw new Conflict("tip_refunded", REFUNDED);
            case "pending", "canceled" -> throw new Conflict("tip_not_charged", NOT_CHARGED);
            default -> {}
        }
        var pi = java.util.Objects.requireNonNull(t.stripePaymentIntent());
        var refund = gateway.refund(
                pi,
                t.amountCents(),
                Map.of("northline_tip_id", tipId, "northline_order_id", t.orderId()),
                StripeIdempotencyKeys.of("refund-tip", tipId));
        var now = clock.instant();
        ledger.post(LedgerEntry.tipRefunded(
                tipId, "allocated".equals(t.state()) ? t.courierUserId() : null, t.amountCents(), now));
        tips.refunded(tipId, reason, staffId, refund, now);
        audit.record(new AuditTrail.Entry(
                null,
                staffId,
                role,
                "payments.tip_refunded",
                "courier_tip",
                tipId,
                Map.of("state", t.state()),
                Map.of("state", "refunded", "reason", reason, "amountCents", String.valueOf(t.amountCents()))));
        return view(tips.find(tipId).orElseThrow(), null);
    }

    private void cancelQuietly(StoredTip t) {
        var pi = t.stripePaymentIntent();
        if (pi == null) {
            return;
        }
        try {
            authorizations.cancel(pi);
        } catch (RuntimeException e) {
            log.warn("Couldn't cancel tip {} payment {}: {}", t.id(), pi, e.getMessage());
        }
    }

    private static Tip view(StoredTip t, @Nullable String clientSecret) {
        return new Tip(
                t.id(),
                t.orderId(),
                t.amountCents(),
                t.source(),
                t.state(),
                t.courierUserId(),
                t.createdAt(),
                clientSecret,
                t.stripePaymentIntent());
    }
}
