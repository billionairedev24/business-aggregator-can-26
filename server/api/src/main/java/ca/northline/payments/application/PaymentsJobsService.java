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

    @Override
    public int releaseDueEscrows() {
        return escrows.releaseDue();
    }

    @Override
    public int renewAuthorizations() {
        return escrows.renewAuthorizations();
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
    public int activatePayoutAccounts() {
        return bankAccounts.activateDue();
    }

    @Override
    public int runPayouts() {
        return payouts.runScheduled() + payouts.settle();
    }
}
