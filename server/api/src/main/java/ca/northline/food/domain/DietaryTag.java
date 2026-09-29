package ca.northline.food.domain;

import ca.northline.shared.CodedEnum;
import java.util.Arrays;
import java.util.Optional;

/** Tags shown under an item (design: Gluten-free, Vegan, Vegetarian, Halal, Spicy, Popular, Vegan option). */
public enum DietaryTag implements CodedEnum {
    GLUTEN_FREE,
    VEGAN,
    VEGETARIAN,
    HALAL,
    SPICY,
    POPULAR,
    VEGAN_OPTION;

    public static Optional<DietaryTag> parse(String code) {
        return Arrays.stream(values()).filter(a -> a.code().equals(code)).findFirst();
    }
}
