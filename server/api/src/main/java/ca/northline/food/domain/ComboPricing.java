package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** A combo's price: a fixed price, or a percentage off the items bought separately. */
public enum ComboPricing implements CodedEnum {
    FIXED,
    PERCENT_OFF
}
