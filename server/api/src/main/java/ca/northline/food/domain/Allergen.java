package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;
import java.util.Arrays;
import java.util.Optional;

/** Health Canada's priority food allergens (11), as declared per menu item. Codes = {@code food.menu_items.allergens}. */
public enum Allergen implements CodedEnum {
    EGGS,
    MILK,
    PEANUTS,
    TREE_NUTS,
    SESAME,
    SOY,
    WHEAT,
    FISH,
    SHELLFISH,
    MUSTARD,
    SULPHITES;

    public static Optional<Allergen> parse(String code) {
        return Arrays.stream(values()).filter(a -> a.code().equals(code)).findFirst();
    }
}
