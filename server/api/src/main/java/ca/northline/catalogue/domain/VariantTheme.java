package ca.northline.catalogue.domain;

import ca.northline.shared.CodedEnum;

/** {@code offers.variant_theme}: how variants of one detail page differ. */
public enum VariantTheme implements CodedEnum {
    NONE,
    SIZE,
    COLOUR,
    SIZE_COLOUR,
    LENGTH,
    LENGTH_POSITION
}
