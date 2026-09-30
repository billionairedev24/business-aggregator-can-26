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
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Refund cases and disputes: the Studio's answers, the customer's side (open a case, answer an offer), agent decisions
 * and the refund queue. Money under review is held: the escrow goes on hold, or — when it was already released — the
 * amount is held back from payouts until the case closes.
 */
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
                n++;
            }
        }
        for (var dispute : cases.expiredOffers(now, 200)) {
            if (dispute.expireOffer(now)) {
                cases.update(dispute);
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
                refund.getAmountCents(),
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
