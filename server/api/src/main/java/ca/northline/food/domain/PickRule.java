package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** How many options of a modifier group a customer picks: exactly N · at least N · up to N. */
public enum PickRule implements CodedEnum {
    EXACTLY,
    AT_LEAST,
    UP_TO
}
