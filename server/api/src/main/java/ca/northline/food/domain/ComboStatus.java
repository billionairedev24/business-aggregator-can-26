package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** {@code food.combos.status}: Draft · Live · Scheduled (live inside its window) · Paused. */
public enum ComboStatus implements CodedEnum {
    DRAFT,
    LIVE,
    SCHEDULED,
    PAUSED
}
