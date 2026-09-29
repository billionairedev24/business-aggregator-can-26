package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** When an item can be ordered (editor "Availability"): always when open, lunch 11–2, after 5 pm, weekends. */
public enum ItemWindow implements CodedEnum {
    ALWAYS,
    LUNCH,
    AFTER_5,
    WEEKENDS
}
