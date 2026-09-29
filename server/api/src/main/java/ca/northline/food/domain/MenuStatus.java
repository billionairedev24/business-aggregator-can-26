package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;

/** {@code food.menus.status}: draft (never published) · live · hidden (taken down). */
public enum MenuStatus implements CodedEnum {
    DRAFT,
    LIVE,
    HIDDEN
}
