package ca.northline.payments.application;

import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Reports each new tax transaction to Stripe Tax after the capture / refund commits (async, from the outbox). */
@Component
@RequiredArgsConstructor
class TaxSyncListener {

    private final TaxSyncService sync;

    @ApplicationModuleListener
    void on(TaxSyncRequested requested) {
        sync.syncOrRecordFailure(requested.reference());
    }
}
