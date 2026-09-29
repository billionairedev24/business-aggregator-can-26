package ca.northline.merchants.domain;

import ca.northline.shared.CodedEnum;

/** {@code merchants.storefronts.cta_label} — the sticky button's label (storefront-sections.json {@code cta_labels}). */
public enum CtaLabel implements CodedEnum {
    BOOK_VISIT,
    REQUEST_QUOTE,
    ORDER_NOW,
    RESERVE;

    /** Services book, shops and kitchens order (design 02 {@code siteDefs.*.cta}). */
    public static CtaLabel defaultFor(MerchantType type) {
        return type == MerchantType.SELLER || type == MerchantType.KITCHEN ? ORDER_NOW : BOOK_VISIT;
    }
}
