package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;

/**
 * {@code availability.calendar_links.state}. {@code RECONNECT}: the provider refused the grant (revoked in the Google
 * or Microsoft account, expired, password change): nothing syncs until the member connects again. Busy blocks already
 * read keep blocking slots meanwhile.
 */
public enum CalendarLinkState implements CodedEnum {
    CONNECTED,
    RECONNECT
}
