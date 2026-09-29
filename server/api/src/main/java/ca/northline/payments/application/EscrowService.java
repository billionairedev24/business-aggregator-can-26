package ca.northline.payments.application;

import ca.northline.payments.api.EscrowLifecycle;
import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.NotFound;
import java.time.Clock;
import java.time.Instant;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Escrow lifecycle: hold (authorized, manual capture) → fulfilled (captured; release clock of the kind starts) →
 * released (net to the merchant's balance + Stripe transfer to the connected account, {@code escrow.released}).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class EscrowService implements EscrowLifecycle {

    private final EscrowRepository escrows;
    private final LedgerRepository ledger;
    private final PayoutRepository payouts;
    private final MerchantTiers tiers;
    private final PaymentGateway gateway;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public String hold(Hold hold) {
        return escrows.findByRef(hold.refType(), hold.refId())
                .map(Escrow::getId)
                .orElseGet(() -> {
                    var now = clock.instant();
                    var intent = escrows.recordPaymentIntent(
                            hold.stripePaymentIntent(), hold.customerId(), hold.amountCents() + hold.taxCents());
                    var escrow =
                            Escrow.hold(hold, tiers.rateOf(hold.merchantId()).takeRateBps(), intent, now);
                    escrows.insert(escrow);
                    return escrow.getId();
                });
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

    /** Releases every due escrow (the release job). */
    int releaseDue() {
        var due = escrows.releasable(clock.instant(), 200);
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

    private void capture(Escrow escrow, Instant at) {
        var intentId = escrow.getPaymentIntentId();
        if (intentId != null) {
            escrows.stripePaymentIntent(intentId)
                    .ifPresent(pi -> gateway.capture(
                            pi, escrow.getAmountCents() + escrow.getTaxCents(), "capture-" + escrow.getId()));
            escrows.markPaymentIntent(intentId, "captured");
        }
        ledger.post(LedgerEntry.captured(escrow, at));
    }

    private void transfer(Escrow escrow, Instant now) {
        payouts.connectedAccount(escrow.getMerchantId())
                .ifPresentOrElse(
                        account -> {
                            var transfer = gateway.transfer(
                                    account.stripeAccount(),
                                    escrow.netCents(),
                                    escrow.getId(),
                                    "transfer-" + escrow.getId());
                            escrows.recordTransfer(
                                    escrow.getId(),
                                    transfer,
                                    escrow.getAmountCents(),
                                    escrow.getFeeCents(),
                                    escrow.netCents(),
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
