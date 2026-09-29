package ca.northline.availability.application;

import ca.northline.availability.domain.CalendarProvider;

/**
 * Outbound port to Google Calendar, Microsoft 365 (two-way sync every 5 min) and the iCal feed. Production adapters do
 * the OAuth handshake and keep the refresh token in KMS ({@code token_ref}); {@code local} and {@code test} use a
 * fake that connects at once.
 */
public interface CalendarSync {

    Connection connect(CalendarProvider provider, String merchantId, String memberUserId);

    void disconnect(CalendarProvider provider, String tokenRef);

    /**
     * @param accountLabel shown under the calendar name ("ravi@prairiewrench.ca")
     * @param tokenRef KMS reference of the OAuth token, or the feed token for iCal
     */
    record Connection(String accountLabel, String tokenRef) {}
}
