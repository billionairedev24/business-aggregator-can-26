package ca.northline.payments.application;

import ca.northline.payments.api.CustomerCases;
import ca.northline.payments.api.DisputeDecided;
import ca.northline.payments.api.DisputeDecisions;
import ca.northline.payments.application.PaymentGateway.IntentStatus;
import ca.northline.payments.domain.CaseMessages;
import ca.northline.payments.domain.ChargedTo;
import ca.northline.payments.domain.Dispute;
import ca.northline.payments.domain.Escrow;
import ca.northline.payments.domain.EscrowState;
import ca.northline.payments.domain.Evidence;
import ca.northline.payments.domain.Fees;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.payments.domain.Refund;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.storage.ObjectKeys;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund cases and disputes: the Studio's answers, the customer's side (open a case, answer an offer), agent decisions
 * and the refund queue. Money under review is held: the escrow goes on hold, or — when it was already released — the
 * amount is held back from payouts until the case closes.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class RefundCaseService implements RespondToCases, CustomerCases, DisputeDecisions {

    private static final int HISTORY = 200;

    private final CaseRepository cases;
    private final EscrowRepository escrows;
    private final EscrowService escrowService;
    private final LedgerRepository ledger;
    private final SalesReadModel sales;
    private final MerchantTiers tiers;
    private final PaymentGateway gateway;
    private final TaxTransactions taxes;
    private final DisputeEvidenceStorage storage;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── Studio ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    @Transactional(readOnly = true)
    public Overview overview(String merchantId) {
        var now = clock.instant();
        var disputes = cases.disputes(merchantId, HISTORY);
        var refunds = cases.refunds(merchantId, HISTORY);
        var monthAgo = now.minus(Duration.ofDays(30));
        return new Overview(
                disputes.stream().filter(d -> !d.decided()).toList(),
                refunds.stream().filter(Refund::awaitingMerchant).toList(),
                refunds,
                disputes,
                (int) refunds.stream()
                        .filter(r -> !r.getCreatedAt().isBefore(monthAgo))
                        .count(),
                sales.disputeRate(merchantId, now.minus(Duration.ofDays(365)), now),
                tiers.tierOf(merchantId));
    }

    @Override
    public Dispute saveResponse(String merchantId, String disputeId, String response) {
        var dispute = dispute(merchantId, disputeId);
        dispute.saveResponse(response, clock.instant());
        cases.update(dispute);
        return dispute;
    }

    @Override
    public Dispute addEvidence(String merchantId, String disputeId, Upload upload) {
        var dispute = dispute(merchantId, disputeId);
        if (upload.bytes().length == 0) {
            throw RuleViolation.of("file", "required", CaseMessages.EVIDENCE_REQUIRED);
        }
        var kind = Evidence.kindOf(upload.contentType())
                .orElseThrow(() -> RuleViolation.of("file", "type", CaseMessages.EVIDENCE_TYPE));
        if (upload.bytes().length > Evidence.MAX_BYTES) {
            throw RuleViolation.of("file", "size", CaseMessages.EVIDENCE_SIZE);
        }
        var id = Ids.next();
        var key = ObjectKeys.merchantObject(merchantId, id, upload.contentType());
        var item = new Evidence(
                id, kind, upload.name(), upload.contentType(), upload.bytes().length, "merchant", clock.instant(), key);
        dispute.addEvidence(item);
        storage.put(key, upload.bytes(), upload.contentType());
        cases.update(dispute);
        return dispute;
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<DisputeEvidenceStorage.StoredFile> evidenceFile(
            String merchantId, String disputeId, String evidenceId) {
        return dispute(merchantId, disputeId).getEvidence().stream()
                .filter(e -> e.id().equals(evidenceId) && e.storageKey() != null)
                .findFirst()
                .flatMap(e -> storage.get(java.util.Objects.requireNonNull(e.storageKey())));
    }

    @Override
    public Dispute offerGoodwill(String merchantId, String disputeId, long amountCents) {
        var dispute = dispute(merchantId, disputeId);
        dispute.offerGoodwill(amountCents, clock.instant());
        cases.update(dispute);
        return dispute;
    }

    @Override
    public Dispute refundInFull(String merchantId, String disputeId, String userId) {
        var dispute = dispute(merchantId, disputeId);
        var decided = dispute.refundInFull(userId, clock.instant());
        cases.update(dispute);
        settle(dispute, decided);
        return dispute;
    }

    @Override
    public Dispute contest(String merchantId, String disputeId) {
        var dispute = dispute(merchantId, disputeId);
        dispute.contest(clock.instant());
        cases.update(dispute);
        return dispute;
    }

    @Override
    public Refund acceptRefund(String merchantId, String refundId) {
        var refund = cases.refund(merchantId, refundId).orElseThrow(() -> new NotFound("refund", refundId));
        refund.accept(clock.instant());
        cases.update(refund);
        return refund;
    }

    @Override
    public Refund contestRefund(String merchantId, String refundId, String reason) {
        var refund = cases.refund(merchantId, refundId).orElseThrow(() -> new NotFound("refund", refundId));
        refund.contest(reason);
        cases.update(refund);
        return refund;
    }

    // ── Customer ──────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public String requestRefund(String escrowId, String customerId, long amountCents, String what) {
        var escrow = customerEscrow(escrowId, customerId);
        var refund = Refund.requested(cases.nextCaseNumber("RF"), escrow, amountCents, what, clock.instant());
        cases.insert(refund);
        escrow.putOnHold();
        escrows.update(escrow);
        events.publishEvent(refund.updated(clock.instant()));
        return refund.getId();
    }

    @Override
    public String openDispute(String escrowId, String customerId, String subject, String statement) {
        var escrow = customerEscrow(escrowId, customerId);
        if (escrow.getState() == EscrowState.REFUNDED) {
            throw new Conflict("escrow_refunded", "This payment was already refunded.");
        }
        var dispute = Dispute.open(cases.nextCaseNumber("DS"), escrow, customerId, subject, statement, clock.instant());
        cases.insert(dispute);
        escrow.putOnHold();
        escrows.update(escrow);
        events.publishEvent(dispute.updated("opened", clock.instant()));
        return dispute.getId();
    }

    @Override
    public void acceptOffer(String disputeId, String customerId) {
        var dispute = cases.dispute(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
        if (!customerId.equals(dispute.getOpenedBy())) {
            throw new NotFound("dispute", disputeId);
        }
        var decided = dispute.acceptOffer(customerId, clock.instant());
        cases.update(dispute);
        settle(dispute, decided);
    }

    @Override
    public void declineOffer(String disputeId, String customerId) {
        var dispute = cases.dispute(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
        if (!customerId.equals(dispute.getOpenedBy())) {
            throw new NotFound("dispute", disputeId);
        }
        dispute.declineOffer(clock.instant());
        cases.update(dispute);
        events.publishEvent(dispute.updated("offer_declined", clock.instant()));
    }

    @Override
    public void decide(String disputeId, Decision decision, long refundCents, String agentId) {
        var dispute = cases.dispute(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
        var outcome = switch (decision) {
            case RELEASE -> Dispute.Decision.RELEASE;
            case PARTIAL -> Dispute.Decision.PARTIAL;
            case FULL_REFUND -> Dispute.Decision.FULL_REFUND;
        };
        var decided = dispute.decide(outcome, refundCents, agentId, clock.instant());
        cases.update(dispute);
        settle(dispute, decided);
    }

    @Override
    public void decideRefund(String refundId, boolean approve, String agentId) {
        var refund = cases.refund(refundId).orElseThrow(() -> new NotFound("refund", refundId));
        refund.decide(approve, clock.instant());
        cases.update(refund);
        events.publishEvent(refund.updated(clock.instant()));
        if (!approve && refund.getEscrowId() != null) {
            escrows.findById(refund.getEscrowId()).ifPresent(escrow -> {
                escrow.resume();
                escrows.update(escrow);
            });
        }
    }

    // ── Jobs ──────────────────────────────────────────────────────────────────────────────────────────────────────

    /** Refund cases the merchant didn't answer in time; goodwill offers nobody answered. */
    int lapse() {
        var now = clock.instant();
        int n = 0;
        for (var refund : cases.lapsedRefunds(now, 200)) {
            if (refund.lapse(now)) {
                cases.update(refund);
                events.publishEvent(refund.updated(now));
                n++;
            }
        }
        for (var dispute : cases.expiredOffers(now, 200)) {
            if (dispute.expireOffer(now)) {
                cases.update(dispute);
                events.publishEvent(dispute.updated("offer_expired", now));
                n++;
            }
        }
        return n;
    }

    /**
     * The refund queue: approved refunds are paid back to the customer. At Stripe: a hold that was never captured is
     * canceled (nothing was charged, nothing is posted); a captured charge is refunded to the card; when the money had
     * already been transferred to the merchant, the merchant-funded part is reversed from that transfer.
     */
    int payQueue() {
        var now = clock.instant();
        var queue = cases.approvedRefunds(100);
        for (var refund : queue) {
            var escrow = refund.getEscrowId() == null
                    ? null
                    : escrows.findById(refund.getEscrowId()).orElse(null);
            boolean fromReleased = true;
            if (escrow != null && refund.getDisputeId() == null) {
                if (!escrow.released() && escrow.getState() != EscrowState.REFUNDED) {
                    if (refund.getAmountCents() >= escrow.getAmountCents()) {
                        escrow.refundInFull();
                        escrows.update(escrow);
                        fromReleased = false;
                    } else {
                        escrowService.releaseAfterCase(escrow);
                    }
                }
            } else if (escrow != null) {
                fromReleased = escrow.released();
            }
            var paid = payAtStripe(refund, escrow, fromReleased);
            var issued = refund.paid(paid.refund(), paid.reversal(), paid.reversedCents(), now);
            cases.update(refund);
            if (!paid.holdCanceled()) {
                ledger.post(LedgerEntry.refunded(refund, fromReleased, now));
                if (refund.getKind() == Refund.Kind.REFUND) {
                    taxes.refunded(refund, now); // the GST/HST given back is reversed at Stripe Tax (S-21)
                }
            }
            events.publishEvent(issued);
        }
        return queue.size();
    }

    /** What the refund queue did at Stripe. */
    private record StripeRefund(
            @Nullable String refund, @Nullable String reversal, long reversedCents, boolean holdCanceled) {}

    private StripeRefund payAtStripe(Refund refund, @Nullable Escrow escrow, boolean fromReleased) {
        if (refund.getKind() == Refund.Kind.CREDIT) {
            return new StripeRefund(null, null, 0, false); // Northline credit: no card money moves
        }
        var intentId = escrow != null && escrow.getPaymentIntentId() != null
                ? escrow.getPaymentIntentId()
                : refund.getPaymentIntentId();
        var intent = intentId == null ? null : escrows.intent(intentId).orElse(null);
        if (intent == null) {
            return new StripeRefund("re_none_" + refund.getId(), null, 0, false);
        }
        if (intent.state() == IntentStatus.AUTHORIZED && escrow != null && escrow.getFulfilledAt() == null) {
            // refund before capture: release the hold instead of charging and refunding
            gateway.cancel(intent.stripePaymentIntent(), StripeIdempotencyKeys.of("cancel", intent.id()));
            escrows.markPaymentIntent(intent.id(), IntentStatus.CANCELED);
            return new StripeRefund(null, null, 0, true);
        }
        var metadata = StripeMetadata.refund(refund, escrow);
        var stripeRefund = gateway.refund(
                intent.stripePaymentIntent(),
                refund.cardCents(), // the amount and the tax on it
                metadata,
                StripeIdempotencyKeys.of("refund", refund.getId()));
        if (!fromReleased || escrow == null || refund.getChargedTo() != ChargedTo.MERCHANT) {
            return new StripeRefund(stripeRefund, null, 0, false);
        }
        var transfer = escrows.transferOf(escrow.getId()).orElse(null);
        if (transfer == null) {
            return new StripeRefund(stripeRefund, null, 0, false);
        }
        var reverse =
                Fees.transferReversalCents(refund.getAmountCents(), transfer.netCents(), transfer.reversedCents());
        if (reverse == 0) {
            return new StripeRefund(stripeRefund, null, 0, false);
        }
        var reversal = gateway.reverseTransfer(
                transfer.stripeTransfer(),
                reverse,
                metadata,
                StripeIdempotencyKeys.of("reverse-transfer", refund.getId()));
        escrows.addReversal(transfer.id(), reverse);
        return new StripeRefund(stripeRefund, reversal, reverse, false);
    }

    // ── Stripe card disputes (chargebacks) ────────────────────────────────────────────────────────────────────────

    /**
     * A {@code charge.dispute.*} webhook. The first event for a Stripe dispute opens a case in the disputes flow (or
     * turns the customer's open case into a card dispute) and puts the escrow on hold; later ones update Stripe's
     * status and deadline — an event older than the last one applied is ignored, and one for a dispute not seen yet
     * opens it first, so any order of arrival ends in the same state. When the bank decides: won → the money stays
     * with the merchant (the escrow's normal release resumes); lost → the merchant carries the amount taken back (from
     * escrow, or from their balance and a transfer reversal when it was released).
     */
    boolean chargeback(StripeEvent event) {
        var o = event.object();
        var stripeDispute = o.id();
        var status = java.util.Objects.requireNonNullElse(o.text("status"), "needs_response");
        var reason = java.util.Objects.requireNonNullElse(o.text("reason"), "general");
        var amount = java.util.Objects.requireNonNullElse(o.number("amount"), 0L);
        var deadline = o.time("evidence_details", "due_by");
        var now = clock.instant();
        if (stripeDispute == null) {
            return false;
        }
        var dispute = cases.disputeByStripeId(stripeDispute).orElse(null);
        Escrow escrow;
        boolean opened = false;
        boolean attached = false;
        if (dispute == null) {
            var pi = o.text("payment_intent");
            var intent = pi == null ? null : escrows.intentByStripeId(pi).orElse(null);
            escrow = intent == null
                    ? null
                    : escrows.findByPaymentIntentId(intent.id()).orElse(null);
            if (escrow == null) {
                log.warn("Stripe dispute {} is on a payment Northline doesn't hold ({})", stripeDispute, pi);
                return false;
            }
            var open = cases.openDisputeOn(escrow.getId()).orElse(null);
            if (open != null) {
                open.attachChargeback(stripeDispute, reason);
                dispute = open;
                attached = true;
            } else {
                dispute = Dispute.chargeback(
                        cases.nextCaseNumber("DS"),
                        escrow,
                        stripeDispute,
                        reason,
                        amount,
                        deadline,
                        event.created(),
                        now);
                opened = true;
            }
            dispute.chargebackUpdated(status, deadline, event.created(), now);
        } else {
            var escrowId = dispute.getEscrowId();
            escrow = escrows.findById(escrowId).orElseThrow(() -> new NotFound("escrow", escrowId));
            if (!dispute.chargebackUpdated(status, deadline, event.created(), now)) {
                return true; // older than what we already applied
            }
        }
        var decided = switch (status) {
            case "won", "warning_closed" -> dispute.chargebackClosed(true, now);
            case "lost" -> dispute.chargebackClosed(false, now);
            default -> Optional.<DisputeDecided>empty();
        };
        if (opened) {
            cases.insert(dispute);
            if (decided.isPresent()) {
                cases.update(dispute); // insert writes an open case; the decision goes in with the update
            }
        } else {
            cases.update(dispute);
        }
        if (opened || attached) {
            escrow.putOnHold();
        }
        if (opened && decided.isEmpty()) {
            events.publishEvent(dispute.updated("opened", now)); // the merchant's team is emailed (S-13)
        }
        var closedDispute = dispute;
        decided.ifPresent(d -> {
            if (d.refundCents() == 0) {
                escrow.resume();
            } else {
                chargebackLost(escrow, closedDispute, amount, now);
            }
            events.publishEvent(d);
        });
        escrows.update(escrow);
        return true;
    }

    /** The bank took {@code stripeAmount} (amount + tax) back: charge the merchant up to the escrow amount. */
    private void chargebackLost(Escrow escrow, Dispute dispute, long stripeAmount, java.time.Instant now) {
        var total = stripeAmount > 0 ? stripeAmount : escrow.getAmountCents() + escrow.getTaxCents();
        var merchantPart = Math.min(total, escrow.getAmountCents());
        var released = escrow.released();
        if (released) {
            escrows.transferOf(escrow.getId()).ifPresent(transfer -> {
                var reverse = Fees.transferReversalCents(merchantPart, transfer.netCents(), transfer.reversedCents());
                if (reverse > 0) {
                    gateway.reverseTransfer(
                            transfer.stripeTransfer(),
                            reverse,
                            java.util.Map.of(
                                    "northline_escrow_id", escrow.getId(),
                                    "northline_merchant_id", escrow.getMerchantId(),
                                    "northline_dispute_id", dispute.getId()),
                            StripeIdempotencyKeys.of("chargeback-reversal", dispute.getId()));
                    escrows.addReversal(transfer.id(), reverse);
                }
            });
        } else if (escrow.getState() != EscrowState.REFUNDED) {
            escrow.refundInFull();
        }
        ledger.post(
                LedgerEntry.chargedBack(escrow, dispute.getId(), merchantPart, total - merchantPart, released, now));
        taxes.chargedBack(
                dispute.getId(),
                escrow.getId(),
                merchantPart,
                Math.min(total - merchantPart, escrow.getTaxCents()),
                now);
    }

    // ── helpers ───────────────────────────────────────────────────────────────────────────────────────────────────

    /** A closed dispute: release what the merchant keeps, queue what the customer gets back. */
    private void settle(Dispute dispute, DisputeDecided decided) {
        var escrow = escrows.findById(dispute.getEscrowId())
                .orElseThrow(() -> new NotFound("escrow", dispute.getEscrowId()));
        var refundCents = decided.refundCents();
        if (refundCents >= escrow.getAmountCents()) {
            escrow.refundInFull();
            escrows.update(escrow);
        } else if (!escrow.released()) {
            escrowService.releaseAfterCase(escrow);
        }
        if (refundCents > 0) {
            cases.insert(Refund.fromDispute(cases.nextCaseNumber("RF"), dispute, escrow, refundCents, clock.instant()));
        }
        events.publishEvent(decided);
    }

    private Dispute dispute(String merchantId, String disputeId) {
        return cases.dispute(merchantId, disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
    }

    private Escrow customerEscrow(String escrowId, String customerId) {
        return escrows.findById(escrowId)
                .filter(e -> customerId.equals(e.getCustomerId()))
                .orElseThrow(() -> new NotFound("escrow", escrowId));
    }
}
