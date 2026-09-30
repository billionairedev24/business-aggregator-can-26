package ca.northline.availability.application;

import ca.northline.availability.application.CalendarSyncEvents.CalendarChanged;
import ca.northline.availability.application.CalendarSyncEvents.CalendarConnected;
import ca.northline.availability.application.CalendarSyncEvents.ChannelRenewalRequested;
import lombok.RequiredArgsConstructor;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/** Runs the sync work after the transaction that asked for it committed (async, retried from the outbox). */
@Component
@RequiredArgsConstructor
class CalendarSyncListener {

    private final CalendarSyncService sync;

    @ApplicationModuleListener
    void on(CalendarConnected event) {
        sync.connected(event.linkId());
    }

    @ApplicationModuleListener
    void on(CalendarChanged event) {
        sync.read(event.linkId(), event.calendarId());
    }

    @ApplicationModuleListener
    void on(ChannelRenewalRequested event) {
        sync.renew(event.channelId(), event.recreate());
    }
}
