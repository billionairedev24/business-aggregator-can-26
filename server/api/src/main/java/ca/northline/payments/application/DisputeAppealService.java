package ca.northline.payments.application;

import ca.northline.payments.api.DisputeAppeals;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link DisputeAppeals} (S-156). */
@Service
@RequiredArgsConstructor
@Transactional
class DisputeAppealService implements DisputeAppeals {

    private final CaseRepository cases;
    private final EscrowRepository escrows;
    private final PayoutRepository payouts;
    private final AppealCompensationStore compensations;
    private final LedgerRepository ledger;
    private final PaymentGateway gateway;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Optional<Facts> facts(String merchantId, String disputeId) {
        return cases.dispute(merchantId, disputeId)
                .map(d -> new Facts(
                        d.getId(),
                        d.getCaseNumber(),
                        d.getSubject(),
                        d.getAmountCents(),
                        d.getDecision() == null ? null : d.getDecision().code(),
                        d.getRefundCents() == null ? 0 : d.getRefundCents(),
                        d.getDecidedBy(),
                        d.getDecidedAt(),
                        compensations.agentDecided(d.getId()),
                        d.isChargeback(),
                        d.getDecisionNote()));
    }

    @Override
    @Transactional(readOnly = true)
    public java.util.List<Facts> appealable(String merchantId, java.time.Instant now) {
        return cases.disputes(merchantId, 200).stream()
                .filter(d -> d.decided() && !d.isChargeback())
                .map(d -> facts(merchantId, d.getId()).orElseThrow())
                .filter(f -> f.appealable(now))
                .toList();
    }

    @Override
    public long compensate(String appealId, String disputeId, String staffId) {
        var existing = compensations.cents(appealId);
        if (existing.isPresent()) {
            return existing.get();
        }
        var dispute = cases.dispute(disputeId).orElseThrow(() -> new NotFound("dispute", disputeId));
        var refund = dispute.getRefundCents() == null ? 0 : dispute.getRefundCents();
        if (refund <= 0) {
            throw new Conflict("nothing_to_compensate", "This decision didn't cost the seller anything.");
        }
        var escrow = escrows.findById(dispute.getEscrowId())
                .orElseThrow(() -> new NotFound("escrow", dispute.getEscrowId()));
        // the seller's share of the refunded amount: what Northline would have paid them on it, after its fee
        var sale = escrow.saleCents();
        var cents = sale <= 0 ? refund : Math.round((double) refund * escrow.netCents() / sale);
        if (cents <= 0) {
            throw new Conflict("nothing_to_compensate", "This decision didn't cost the seller anything.");
        }
        var now = clock.instant();
        if (!compensations.insert(appealId, disputeId, dispute.getMerchantId(), cents, staffId, now)) {
            throw new Conflict("already_compensated", "The seller was already paid for this dispute.");
        }
        ledger.post(LedgerEntry.appealCompensated(appealId, dispute.getMerchantId(), cents, now));
        payouts.connectedAccount(dispute.getMerchantId())
                .ifPresent(account -> compensations.transfer(
                        appealId,
                        gateway.transfer(new PaymentGateway.Transfer(
                                account.stripeAccount(),
                                cents,
                                StripeMetadata.defaultTransferGroup("appeal", appealId),
                                null,
                                Map.of(
                                        "northline_purpose", "appeal_compensation",
                                        "northline_appeal_id", appealId,
                                        "northline_dispute_id", disputeId),
                                StripeIdempotencyKeys.of("appeal-compensation", appealId)))));
        return cents;
    }
}
