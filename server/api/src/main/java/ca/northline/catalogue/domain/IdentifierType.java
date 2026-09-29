package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code catalog_products.identifier_type}: which product identifier the seller entered. */
public enum IdentifierType implements CodedEnum {
    GTIN,
    EAN,
    ISBN,
    NONE
}
