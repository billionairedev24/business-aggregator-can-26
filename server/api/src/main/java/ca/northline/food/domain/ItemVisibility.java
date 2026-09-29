package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/**
 * What the customer side sees of an item, and why not (Menu builder status tag). A published item goes live only when
 * the kitchen is approved and the allergen audit passes (allergens declared, own photo on file) — "items stay hidden
 * until the kitchen is approved". {@code vetting()} is what {@code food.menu_items.vetting} stores.
 */
public enum ItemVisibility implements CodedEnum {
    DRAFT,
    NEEDS_PHOTO,
    AWAITING_APPROVAL,
    LIVE;

    public static ItemVisibility of(
            ItemStatus status, boolean allergensDeclared, boolean hasPhoto, boolean kitchenApproved) {
        if (status == ItemStatus.DRAFT || !allergensDeclared) {
            return DRAFT;
        }
        if (!hasPhoto) {
            return NEEDS_PHOTO;
        }
        return kitchenApproved ? LIVE : AWAITING_APPROVAL;
    }

    /** {@code draft | pending | approved}. */
    public String vetting() {
        return switch (this) {
            case DRAFT -> "draft";
            case NEEDS_PHOTO, AWAITING_APPROVAL -> "pending";
            case LIVE -> "approved";
        };
    }
}
