package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** A food order on the kitchen display: New → Cooking → Ready → handed off (to the courier or the customer). */
public enum KitchenStage implements CodedEnum {
    NEW,
    COOKING,
    READY,
    HANDED_OFF,
    /** Age-restricted dishes: nobody of age with photo ID came for the pickup; not handed over (2026-10-04). */
    REFUSED
}
