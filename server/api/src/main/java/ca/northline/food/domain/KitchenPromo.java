package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** Northline-funded promos a kitchen can opt into (Modifiers &amp; combos). */
public enum KitchenPromo implements CodedEnum {
    /** "Fund 3× points on Pho for two this week" — $0.02 per point. */
    POINTS_3X,
    /** "First-order $5 off (you pay $3, Northline $2)". */
    FIRST_ORDER_5
}
