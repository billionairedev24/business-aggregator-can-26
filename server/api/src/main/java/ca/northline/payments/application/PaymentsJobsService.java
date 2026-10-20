package ca.northline.payments.application;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Entry point of the background jobs; each step runs in the owning service's transaction. */
@Service
@RequiredArgsConstructor
class PaymentsJobsService implements PaymentsJobs {

    private final EscrowService escrows;
    private final RefundCaseService cases;
    private final BankAccountService bankAccounts;
    private final PayoutService payouts;
    private final StripeEventProcessor stripeEvents;
    private final TaxSyncService tax;
    private final CourierPayoutService couriers;
    private final UnheldPaymentSweeper unheld;

    @Override
    public int releaseDueEscrows() {
        return escrows.releaseDue();
    }

    @Override
    public int renewAuthorizations() {
        return escrows.renewAuthorizations();
    }

    @Override
    public int cancelUnheldBookingPayments() {
        return unheld.sweep();
    }

    @Override
    public int processStripeEvents() {
        return stripeEvents.processPending();
    }

    @Override
    public int lapseCases() {
        return cases.lapse();
    }

    @Override
    public int payRefundQueue() {
        return cases.payQueue();
    }

    @Override
    public int syncTax() {
        return tax.syncPending();
    }

    @Override
    public int reconcileTax() {
        return tax.reconcileRecent();
    }

    @Override
    public int activatePayoutAccounts() {
        return bankAccounts.activateDue();
    }

    @Override
    public int runPayouts() {
        return payouts.runScheduled() + payouts.settle();
    }

    @Override
    public int payCouriers() {
        return couriers.payAll();
    }

    @Override
    public int settleCourierPayouts() {
        return couriers.settle();
    }
}
