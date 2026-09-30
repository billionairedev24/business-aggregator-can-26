package ca.northline.catalogue.application;

import ca.northline.catalogue.application.CommerceSyncEvents.CommerceConnected;
import ca.northline.catalogue.application.CommerceSyncEvents.CommerceProductChanged;
import ca.northline.catalogue.application.CommerceSyncEvents.CommerceProductRemoved;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Runs the S-35 sync work after the transaction that asked for it committed (async, retried from the outbox). */
@Component
@RequiredArgsConstructor
class CommerceSyncListener {

    private final CommerceSyncService sync;

    @ApplicationModuleListener
    void on(CommerceConnected event) {
        sync.connected(event.integrationId());
    }

    @ApplicationModuleListener
    void on(CommerceProductChanged event) {
        sync.productChanged(event.integrationId(), event.externalId());
    }

    @ApplicationModuleListener
    void on(CommerceProductRemoved event) {
        sync.productRemoved(event.integrationId(), event.externalId());
    }
}
