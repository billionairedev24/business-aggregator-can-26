package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;

/**
 * Calendar sync providers: Google Calendar and Outlook / Microsoft 365 sync two-way over OAuth (S-32); iCal is a
 * read-only subscription feed.
 */
public enum CalendarProvider implements CodedEnum {
    GOOGLE,
    OUTLOOK,
    ICAL;

    public boolean twoWay() {
        return this != ICAL;
    }
}
