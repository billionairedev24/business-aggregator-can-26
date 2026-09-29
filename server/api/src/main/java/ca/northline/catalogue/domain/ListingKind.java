package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** What a listing sells: a bookable service or a product offer. */
public enum ListingKind implements CodedEnum {
    SERVICE,
    PRODUCT
}
