package ca.northline.booking.domain;

import ca.northline.shared.CodedEnum;

/** {@code booking.bookings.state}. The merchant drives confirmed → en route → on site → completed. */
public enum BookingState implements CodedEnum {
    REQUESTED,
    CONFIRMED,
    EN_ROUTE,
    ON_SITE,
    COMPLETED,
    SIGNED_OFF,
    DISPUTED,
    CANCELLED;

    /** Jobs that still need the merchant (mid-job approvals are allowed here). */
    public boolean isActive() {
        return this == CONFIRMED || this == EN_ROUTE || this == ON_SITE;
    }
}
