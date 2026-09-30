package ca.northline.availability.application;

/**
 * Internal events of the S-32 sync (not in {@code api}, not externalized). Published in the transaction that caused
 * them, so the Modulith registry (outbox) runs the work after commit and again after a restart if it didn't finish.
 */
public final class CalendarSyncEvents {
    private CalendarSyncEvents() {}

    /** A member connected (or re-chose calendars): read every source, open notification channels, write bookings. */
    public record CalendarConnected(String linkId) {}

    /** A verified change notification for one source: read its changes now. */
    public record CalendarChanged(String linkId, String calendarId) {}

    /** Graph asked for a renewal (lifecycle {@code reauthorizationRequired} / {@code subscriptionRemoved}). */
    public record ChannelRenewalRequested(String channelId, boolean recreate) {}
}
