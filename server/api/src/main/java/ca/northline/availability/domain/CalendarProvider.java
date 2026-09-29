package ca.northline.availability.domain;

import ca.northline.shared.CodedEnum;

/** Calendar sync providers: Google and Outlook sync two-way; iCal is a read-only subscription feed. */
public enum CalendarProvider implements CodedEnum {
    GOOGLE,
    OUTLOOK,
    ICAL;

    public boolean twoWay() {
        return this != ICAL;
    }
}
