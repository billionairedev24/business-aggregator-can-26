package ca.northline.payments.application;

import ca.northline.payments.domain.LedgerEntry;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The merchant's money that has left escrow: the credit balance of {@code merchant:<id>} in the ledger, minus what open
 * refund cases on already-released money hold back ("seller payout paused meanwhile", chat 1).
 */
@Component
@RequiredArgsConstructor
class MerchantBalances {

    private final LedgerRepository ledger;
    private final EarningsReadModel earnings;

    record Available(long balanceCents, long heldForCasesCents, int heldCases) {
        long availableCents() {
            return Math.max(0, balanceCents - heldForCasesCents);
        }
    }

    Available of(String merchantId) {
        var holds = earnings.refundHolds(merchantId);
        return new Available(ledger.balance(LedgerEntry.merchant(merchantId)), holds.cents(), holds.count());
    }
}
