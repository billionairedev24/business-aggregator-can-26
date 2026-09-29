package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** Codes of {@code food.kitchen_settings.fulfilment}: Northline direct courier (hot), pickup, meal kits, scheduled. */
public enum FulfilmentOption implements CodedEnum {
    COURIER,
    PICKUP,
    MEAL_KITS,
    SCHEDULED
}
