package ca.northline.account.domain;

import ca.northline.shared.CodedEnum;

/** What a row of "Orders &amp; bookings" is. */
public enum ActivityKind implements CodedEnum {
    /** A shop order (pooled run or direct courier). */
    ORDER,
    /** A food order (delivery or pickup). */
    FOOD,
    /** A booked job (direct booking or an accepted quote). */
    BOOKING,
    /** A quote request still waiting for, or holding, open quotes. */
    QUOTE
}
