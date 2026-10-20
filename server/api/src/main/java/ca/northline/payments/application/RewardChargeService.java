package ca.northline.payments.application;

import ca.northline.payments.api.RewardCharges;
import ca.northline.payments.domain.Fees;
import ca.northline.payments.domain.LedgerEntry;
import ca.northline.shared.Ids;
import ca.northline.shared.stripe.StripeIdempotencyKeys;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link RewardCharges}: the ledger moves the reward's cost from the business's balance to {@code rewards_funded}; the
 * money itself comes out of the sale's transfer (a transfer reversal, so it never reaches the business's payouts). A
 * sale without a transfer (no connected account yet) is paid by the lower ledger balance, which is what payouts read.
 * A refund's share goes back by a transfer to the business's account (or the ledger alone without one).
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional
class RewardChargeService implements RewardCharges {

    private final RewardChargeStore store;
    private final EscrowRepository escrows;
    private final PayoutRepository payouts;
    private final PaymentGateway gateway;
    private final LedgerRepository ledger;
    private final Clock clock;

    @Override
    public void charge(String ref, String merchantId, String escrowId, long cents) {
        if (cents <= 0) {
            return;
        }
        var id = Ids.next();
        var now = clock.instant();
        if (!store.insert(id, ref, "charge", merchantId, escrowId, cents, now)) {
            return; // already charged
        }
        ledger.post(LedgerEntry.rewardCharged(id, merchantId, cents, now));
        var transfer = escrows.transferOf(escrowId).orElse(null);
        if (transfer != null) {
            var amount = Fees.transferReversalCents(cents, transfer.netCents(), transfer.reversedCents());
            if (amount > 0) {
                store.stripeObject(
                        id,
                        gateway.reverseTransfer(
                                transfer.stripeTransfer(),
                                amount,
                                metadata(ref, escrowId),
                                StripeIdempotencyKeys.of("reward-charge", ref)));
                escrows.addReversal(transfer.id(), amount);
            }
        }
    }

    @Override
    public void refund(String ref, String merchantId, String escrowId, long cents) {
        if (cents <= 0) {
            return;
        }
        var id = Ids.next();
        var now = clock.instant();
        if (!store.insert(id, ref, "refund", merchantId, escrowId, cents, now)) {
            return;
        }
        ledger.post(LedgerEntry.rewardRefunded(id, merchantId, cents, now));
        var account = payouts.connectedAccount(merchantId).orElse(null);
        if (account != null && escrows.transferOf(escrowId).isPresent()) {
            store.stripeObject(
                    id,
                    gateway.transfer(new PaymentGateway.Transfer(
                            account.stripeAccount(),
                            cents,
                            StripeMetadata.defaultTransferGroup("reward", escrowId),
                            null,
                            metadata(ref, escrowId),
                            StripeIdempotencyKeys.of("reward-refund", ref))));
        }
    }

    private static Map<String, String> metadata(String ref, String escrowId) {
        return Map.of(
                "northline_purpose", "reward_charge", "northline_reward_ref", ref, "northline_escrow_id", escrowId);
    }
}
