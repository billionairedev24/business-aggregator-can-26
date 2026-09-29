package ca.northline.payments;

import ca.northline.booking.api.QuoteAccepted;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Async, own transaction, retried from the registry until it completes. Idempotent on eventId. */
@Component
class EscrowOnQuoteAccepted {
    private final EscrowService escrow;

    EscrowOnQuoteAccepted(EscrowService escrow) {
        this.escrow = escrow;
    }

    @ApplicationModuleListener
    void on(QuoteAccepted e) {
        if (e.depositCents() > 0)
            escrow.authorizeDeposit(e.eventId(), e.aggregateId(), e.customerId(), e.depositCents());
    }
}
