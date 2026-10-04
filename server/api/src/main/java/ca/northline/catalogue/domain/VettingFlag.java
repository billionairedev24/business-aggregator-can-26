package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** Why automated vetting sent a listing to manual review ({@code vetting_flags}). */
public enum VettingFlag implements CodedEnum {
    BANNED_CATEGORY,
    PRICE_OUTLIER,
    MISSING_LICENCE,
    DUPLICATE_IMAGE,
    MAIN_NOT_ON_WHITE,
    /** S-93: the listing uses a restricted keyword (trust &amp; safety rules). */
    RESTRICTED_KEYWORD,
    /** 2026-10-04: an age-restricted category — every such listing is vetted by a person ("wines should be vetted"). */
    AGE_RESTRICTED,
    /** 2026-10-04: words of an age-restricted product (wine, vape …) in a category without an age class. */
    AGE_CLASS_MISMATCH
}
