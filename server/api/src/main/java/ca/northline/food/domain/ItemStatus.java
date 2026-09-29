package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** {@code food.menu_items.status}: the kitchen's intent — "Save as draft" or "Save &amp; publish". */
public enum ItemStatus implements CodedEnum {
    DRAFT,
    PUBLISHED
}
