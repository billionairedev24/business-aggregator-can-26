package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;

/**
 * What Northline asks a calendar provider for, mapped to each provider's scopes by its adapter. {@code EVENTS} on
 * connect (read busy times, write bookings); {@code CALENDAR_LIST} only when the member opens "Choose calendars"
 * (incremental consent). Microsoft's {@code Calendars.ReadWrite} already lists calendars, so it grants both at once.
 */
public enum CalendarScope implements CodedEnum {
    EVENTS,
    CALENDAR_LIST
}
