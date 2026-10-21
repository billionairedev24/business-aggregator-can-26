package ca.northline.standing.domain;

import ca.northline.shared.CodedEnum;

/** {@code standing.consequences.kind}. */
public enum ConsequenceKind implements CodedEnum {
    /** The business is offline (merchants status {@code suspended}). */
    SUSPENDED,
    /** Hidden from search; its page and checkout stay open. */
    SEARCH_HIDDEN,
    /** Northline paused instant book: customers ask for a quote first. */
    INSTANT_BOOK_OFF,
    /** One job's escrow is released later: completed without a photo. */
    PHOTO_DELAY,
    /** A customer's confirmed no-shows: providers see them and confirm bookings first. */
    NO_SHOW_WARNING;

    /** What a business may appeal (once each). */
    public boolean appealable() {
        return this == SUSPENDED || this == SEARCH_HIDDEN || this == INSTANT_BOOK_OFF;
    }

    public boolean ofCustomer() {
        return this == NO_SHOW_WARNING;
    }
}
