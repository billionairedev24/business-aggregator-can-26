package ca.northline.payments.application;

import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.api.PaymentReauthorizationRequired;
import ca.northline.payments.application.PaymentGateway.Authorization;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.domain.AuthorizationWindow;
import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.Fees;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Instant;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrow lifecycle: hold (Stripe PaymentIntent authorized, manual capture) → fulfilled (captured; release clock of the
 * kind starts) → released (net to the merchant's balance + Stripe transfer to the connected account in the
 * PaymentIntent's transfer group, {@code escrow.released}). Holds still waiting for capture are renewed before Stripe
 * lets them lapse ({@link AuthorizationWindow}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class EscrowService implements EscrowLifecycle {

    private static final int BATCH = 200;

    private final EscrowRepository escrows;
    private final LedgerRepository ledger;
    private final PayoutRepository payouts;
    private final MerchantTiers tiers;
    private final PaymentGateway gateway;
    private final TaxTransactions taxes;
    private final TaxRepository deliveryTax;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public String hold(Hold hold) {
        return escrows.findByRef(hold.refType(), hold.refId())
                .map(Escrow::getId)
                .orElseGet(() -> {
                    var now = clock.instant();
                    var total = hold.amountCents() + hold.taxCents() + hold.platformTotal();
                    var authorization = requireAuthorized(gateway.authorization(hold.stripePaymentIntent()), total);
                    var known = escrows.intentByStripeId(hold.stripePaymentIntent());
                    var intent = escrows.recordPaymentIntent(new EscrowRepository.IntentRecord(
                            hold.stripePaymentIntent(),
                            IntentStatus.AUTHORIZED,
                            hold.customerId(),
                            total,
                            authorization.stripeCustomer(),
                            authorization.paymentMethod(),
                            authorization.charge(),
                            Objects.requireNonNullElseGet(
                                    authorization.transferGroup(),
                                    () -> known.map(EscrowRepository.Intent::transferGroup)
                                            .orElseGet(() ->
                                                    StripeMetadata.defaultTransferGroup(hold.refType(), hold.refId()))),
                            hold.refType(),
                            hold.refId(),
                            hold.merchantId(),
                            now,
                            authorization.captureBefore(),
                            0));
                    var escrow =
                            Escrow.hold(hold, tiers.rateOf(hold.merchantId()).takeRateBps(), intent, now);
                    escrows.insert(escrow);
                    return escrow.getId();
                });
    }

    /** Only money Stripe really holds for us becomes escrow. */
    private static Authorization requireAuthorized(Authorization authorization, long totalCents) {
        if (authorization.status() != IntentStatus.AUTHORIZED) {
            throw new Conflict("payment_not_authorized", "The card payment isn't authorized yet.");
        }
        if (authorization.amountCapturableCents() > 0 && authorization.amountCapturableCents() < totalCents) {
            throw new Conflict("payment_amount_mismatch", "The card was authorized for less than the amount due.");
        }
        return authorization;
    }

    @Override
    public void fulfilled(String refType, String refId, Instant at) {
        var escrow = byRef(refType, refId);
        var wasFulfilled = escrow.getFulfilledAt() != null;
        escrow.fulfil(at);
        if (!wasFulfilled) {
            capture(escrow, at);
        }
        releaseIfDue(escrow);
        escrows.update(escrow);
    }

    @Override
    public boolean fulfilledIfHeld(String refType, String refId, Instant at) {
        if (escrows.findByRef(refType, refId).isEmpty()) {
            return false;
        }
        fulfilled(refType, refId, at);
        return true;
    }

    @Override
    public void confirmed(String refType, String refId, Instant at) {
        var escrow = byRef(refType, refId);
        var wasFulfilled = escrow.getFulfilledAt() != null;
        escrow.confirm(at);
        if (!wasFulfilled) {
            capture(escrow, at);
        }
        releaseIfDue(escrow);
        escrows.update(escrow);
    }

    @Override
    public boolean confirmedIfHeld(String refType, String refId, Instant at) {
        if (escrows.findByRef(refType, refId).isEmpty()) {
            return false;
        }
        confirmed(refType, refId, at);
        return true;
    }

    @Override
    public boolean captureDeliveryFee(String orderId, Instant at) {
        var intent = escrows.currentIntentForUpdate(LedgerEntry.DELIVERY_FEE, orderId)
                .filter(i -> i.state() == IntentStatus.AUTHORIZED)
                .orElse(null);
        if (intent == null) {
            return false;
        }
        var charge = gateway.capture(
                intent.stripePaymentIntent(),
                intent.amountCents(),
                StripeIdempotencyKeys.of("capture-delivery", orderId, intent.id()));
        escrows.recordCapture(intent.id(), charge);
        var tax = Math.min(
                intent.amountCents(),
                deliveryTax.calculationFor(LedgerEntry.DELIVERY_FEE, orderId)
                        .map(TaxRepository.Calculation::taxCents)
                        .orElse(0L));
        ledger.post(LedgerEntry.deliveryFeeCaptured(orderId, intent.amountCents(), tax, at));
        log.info("Order {}: delivery fee captured ({} cents)", orderId, intent.amountCents());
        return true;
    }

    /** Releases every due escrow (the release job). */
    int releaseDue() {
        var due = escrows.releasable(clock.instant(), BATCH);
        due.forEach(e -> {
            releaseIfDue(e);
            escrows.update(e);
        });
        return due.size();
    }

    /** Moves a due escrow's net to the merchant; a no-op when it isn't due or is on hold. */
    void releaseIfDue(Escrow escrow) {
        var now = clock.instant();
        escrow.release(now).ifPresent(released -> {
            transfer(escrow, now);
            ledger.post(LedgerEntry.released(escrow, now));
            events.publishEvent(released);
        });
    }

    /** Releases an escrow a case held (won dispute, partial refund): same postings as a normal release. */
    void releaseAfterCase(Escrow escrow) {
        var now = clock.instant();
        if (escrow.getFulfilledAt() == null) {
            capture(escrow, now);
        }
        var released = escrow.releaseAfterCase(now);
        transfer(escrow, now);
        ledger.post(LedgerEntry.released(escrow, now));
        escrows.update(escrow);
        events.publishEvent(released);
    }

    /** The re-authorization job: renews holds about to lapse at Stripe; returns how many were renewed. */
    int renewAuthorizations() {
        var now = clock.instant();
        int renewed = 0;
        for (var escrow : escrows.authorizationsLapsingBefore(now.plus(AuthorizationWindow.LEAD), BATCH)) {
            var intentId = escrow.getPaymentIntentId();
            var intent = intentId == null ? null : escrows.intent(intentId).orElse(null);
            if (intent == null
                    || intent.authorizedAt() == null
                    || !AuthorizationWindow.renewalDue(
                            intent.captureBefore(), intent.authorizedAt(), intent.reauthFailedAt(), now)) {
                continue;
            }
            if (renew(escrow, intent, now)) {
                renewed++;
            }
        }
        return renewed;
    }

    /**
     * A new manual-capture PaymentIntent for the same amount with the saved card, off-session; only when Stripe
     * authorizes it is the old hold canceled and the escrow moved over — the money is never unheld in between.
     */
    private boolean renew(Escrow escrow, EscrowRepository.Intent intent, Instant now) {
        var lapsesAt =
                AuthorizationWindow.lapsesAt(intent.captureBefore(), Objects.requireNonNull(intent.authorizedAt()));
        var customer = intent.stripeCustomer();
        var card = intent.paymentMethod();
        if (customer == null || card == null) {
            renewalFailed(escrow, intent, lapsesAt, now, "no saved card");
            return false;
        }
        var group = Objects.requireNonNullElseGet(
                intent.transferGroup(),
                () -> StripeMetadata.defaultTransferGroup(escrow.getRefType(), escrow.getRefId()));
        var attempt = String.valueOf(intent.reauthorizations() + 1);
        Authorization next;
        try {
            next = gateway.authorize(new PaymentGateway.Authorize(
                    intent.amountCents(),
                    group,
                    customer,
                    card,
                    true,
                    StripeMetadata.escrow(escrow),
                    StripeIdempotencyKeys.of("reauthorize", escrow.getId(), attempt)));
        } catch (RuntimeException e) {
            renewalFailed(escrow, intent, lapsesAt, now, e.getMessage());
            return false;
        }
        if (next.status() != IntentStatus.AUTHORIZED) {
            // needs the customer (3-D Secure) or was declined: drop the attempt, keep the old hold
            if (next.status() == IntentStatus.REQUIRES_ACTION) {
                gateway.cancel(
                        next.paymentIntent(),
                        StripeIdempotencyKeys.of("cancel-reauthorization", escrow.getId(), attempt));
            }
            renewalFailed(escrow, intent, lapsesAt, now, next.status().code());
            return false;
        }
        var renewedId = escrows.recordPaymentIntent(new EscrowRepository.IntentRecord(
                next.paymentIntent(),
                IntentStatus.AUTHORIZED,
                Objects.requireNonNullElse(intent.customerId(), Objects.requireNonNullElse(escrow.getCustomerId(), "")),
                intent.amountCents(),
                customer,
                card,
                next.charge(),
                group,
                escrow.getRefType(),
                escrow.getRefId(),
                escrow.getMerchantId(),
                now,
                next.captureBefore(),
                intent.reauthorizations() + 1));
        escrow.reauthorized(renewedId);
        escrows.update(escrow);
        gateway.cancel(intent.stripePaymentIntent(), StripeIdempotencyKeys.of("cancel", intent.id()));
        escrows.replacePaymentIntent(intent.id(), renewedId);
        log.info(
                "Escrow {}: card hold renewed ({} → {})",
                escrow.getId(),
                intent.stripePaymentIntent(),
                next.paymentIntent());
        return true;
    }

    private void renewalFailed(
            Escrow escrow, EscrowRepository.Intent intent, Instant lapsesAt, Instant now, @Nullable String why) {
        log.warn(
                "Escrow {}: card hold {} could not be renewed ({}); it lapses at {}",
                escrow.getId(),
                intent.stripePaymentIntent(),
                why,
                lapsesAt);
        if (intent.reauthFailedAt() == null) {
            events.publishEvent(new PaymentReauthorizationRequired(
                    Ids.next(),
                    now,
                    escrow.getId(),
                    escrow.getMerchantId(),
                    Objects.requireNonNullElse(
                            escrow.getCustomerId(), Objects.requireNonNullElse(intent.customerId(), "")),
                    escrow.getRefType(),
                    escrow.getRefId(),
                    lapsesAt));
        }
        escrows.reauthorizationFailed(intent.id(), now);
    }

    private void capture(Escrow escrow, Instant at) {
        var intentId = escrow.getPaymentIntentId();
        if (intentId != null) {
            escrows.intent(intentId).ifPresent(intent -> {
                if (intent.state() == IntentStatus.CAPTURED) {
                    return; // captured by an earlier attempt
                }
                var charge = gateway.capture(
                        intent.stripePaymentIntent(),
                        escrow.capturedCents(),
                        StripeIdempotencyKeys.of("capture", escrow.getId(), intent.id()));
                escrows.recordCapture(intent.id(), charge);
            });
        }
        ledger.post(LedgerEntry.captured(escrow, at));
        taxes.captured(escrow, at); // reported to Stripe Tax after commit (S-21)
    }

    private void transfer(Escrow escrow, Instant now) {
        payouts.connectedAccount(escrow.getMerchantId())
                .ifPresentOrElse(
                        account -> {
                            var intent = escrow.getPaymentIntentId() == null
                                    ? null
                                    : escrows.intent(escrow.getPaymentIntentId())
                                            .orElse(null);
                            var group = intent != null && intent.transferGroup() != null
                                    ? intent.transferGroup()
                                    : StripeMetadata.defaultTransferGroup(escrow.getRefType(), escrow.getRefId());
                            var net = Fees.transferCents(escrow.getAmountCents(), escrow.getFeeCents());
                            var transfer = gateway.transfer(new PaymentGateway.Transfer(
                                    account.stripeAccount(),
                                    net,
                                    group,
                                    intent == null ? null : intent.charge(),
                                    StripeMetadata.escrow(escrow),
                                    StripeIdempotencyKeys.of("transfer", escrow.getId())));
                            escrows.recordTransfer(
                                    escrow.getId(),
                                    transfer,
                                    group,
                                    escrow.getAmountCents(),
                                    escrow.getFeeCents(),
                                    net,
                                    now);
                        },
                        () -> log.warn(
                                "Merchant {} has no connected account; escrow {} released without a transfer",
                                escrow.getMerchantId(),
                                escrow.getId()));
    }

    private Escrow byRef(String refType, String refId) {
        return escrows.findByRef(refType, refId).orElseThrow(() -> new NotFound("escrow", refType + ":" + refId));
    }
}
