package ca.northline.availability.persistence;

import ca.northline.availability.application.CalendarSync;
import ca.northline.availability.domain.CalendarProvider;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import java.util.Locale;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/** {@link CalendarSync} adapters. The Google / Microsoft OAuth adapters are not wired yet (docs/DECISIONS.md). */
final class CalendarSyncAdapters {
    private CalendarSyncAdapters() {}

    /** Local development and tests: connects at once with a placeholder account; iCal gets a random feed token. */
    @Component
    @Profile({"local", "test"})
    static class FakeCalendarSync implements CalendarSync {
        @Override
        public Connection connect(CalendarProvider provider, String merchantId, String memberUserId) {
            var token = Ids.next().toLowerCase(Locale.ROOT);
            return switch (provider) {
                case GOOGLE -> new Connection("Google account (local fake)", "fake:google:" + token);
                case OUTLOOK -> new Connection("Microsoft 365 account (local fake)", "fake:outlook:" + token);
                case ICAL -> new Connection("Read-only feed", token);
            };
        }

        @Override
        public void disconnect(CalendarProvider provider, String tokenRef) {
            // nothing to revoke
        }
    }

    /** Other profiles until the OAuth adapters land: 409 {@code calendar_sync_unavailable}. */
    @Component
    @Profile("!local & !test")
    static class UnavailableCalendarSync implements CalendarSync {
        @Override
        public Connection connect(CalendarProvider provider, String merchantId, String memberUserId) {
            throw new Conflict("calendar_sync_unavailable", "Calendar sync is not available yet.");
        }

        @Override
        public void disconnect(CalendarProvider provider, String tokenRef) {
            // nothing was connected
        }
    }
}
