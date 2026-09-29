package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code catalogue.integrations.provider}: external catalogues a merchant can sync price and stock from. */
public enum CommerceProvider implements CodedEnum {
    SHOPIFY,
    SQUARE,
    LIGHTSPEED
}
