package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.status} / {@code services.status}: the merchant's visibility intent. Customers see a listing only when it is approved AND live. */
public enum ListingStatus implements CodedEnum {
    LIVE,
    HIDDEN
}
