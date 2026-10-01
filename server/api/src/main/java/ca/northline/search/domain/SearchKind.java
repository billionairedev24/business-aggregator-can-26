package ca.northline.search.domain;

import ca.northline.shared.CodedEnum;

/** What a search result is: a bookable service, a product offer, a dish, or the business itself. */
public enum SearchKind implements CodedEnum {
    SERVICE,
    PRODUCT,
    FOOD,
    MERCHANT
}
