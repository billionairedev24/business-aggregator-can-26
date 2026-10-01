package ca.northline.account.domain;

import ca.northline.shared.CodedEnum;

/** The row's action button (design 06: "Track", "Details", "View quote", "Re-book", "View case"). */
public enum ActivityAction implements CodedEnum {
    TRACK,
    DETAILS,
    VIEW_QUOTE,
    REBOOK,
    VIEW_CASE
}
