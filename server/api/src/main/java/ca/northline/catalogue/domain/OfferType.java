package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.listing_type} (S-65): a product, or a bundle of the business's own products. */
public enum OfferType implements CodedEnum {
    PRODUCT,
    BUNDLE
}
