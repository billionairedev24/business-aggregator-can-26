package ca.northline.food.application;

import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.application.MenuUseCases.ItemCommand;
import ca.northline.food.domain.Allergen;
import ca.northline.food.domain.DietaryTag;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.shared.RuleViolation.Violation;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Item rules shared by the editor and the CSV import (Bean Validation covers the request shape; these need the
 * kitchen's data): the section belongs to the menu, allergens / dietary tags are known codes, prep is one of the
 * options, modifier groups are the kitchen's own. Messages from {@link KitchenMessages}.
 */
final class MenuItemRules {
    private MenuItemRules() {}

    static final Set<Integer> PREP_OPTIONS = Set.of(0, 5, 10);

    /** Adds the broken rules of {@code c} to {@code out}, fields prefixed with {@code prefix} ("" or "rows[3]."). */
    static void check(
            String prefix,
            ItemCommand c,
            @Nullable SectionRow section,
            Set<String> ownedGroupIds,
            Collection<Violation> out) {
        if (section == null || !section.menuId().equals(c.menuId())) {
            out.add(new Violation(prefix + "sectionId", "exists", KitchenMessages.MENU_AND_SECTION));
        }
        if (c.name().strip().isEmpty()) {
            out.add(new Violation(prefix + "name", "required", KitchenMessages.ITEM_NAME));
        }
        if (c.priceCents() <= 0) {
            out.add(new Violation(prefix + "priceCents", "range", KitchenMessages.PRICE));
        }
        if (c.allergens().stream().anyMatch(a -> Allergen.parse(a).isEmpty())) {
            out.add(new Violation(prefix + "allergens", "allergen", KitchenMessages.ALLERGEN_LIST));
        }
        if (c.dietary().stream().anyMatch(d -> DietaryTag.parse(d).isEmpty())) {
            out.add(new Violation(prefix + "dietary", "dietary", KitchenMessages.DIETARY_LIST));
        }
        if (!PREP_OPTIONS.contains(c.prepAddMin())) {
            out.add(new Violation(prefix + "prepAddMin", "option", KitchenMessages.OPTION));
        }
        if (c.dailyLimit() != null && (c.dailyLimit() < 1 || c.dailyLimit() > 999)) {
            out.add(new Violation(prefix + "dailyLimit", "range", KitchenMessages.DAILY_LIMIT));
        }
        if (!ownedGroupIds.containsAll(c.modifierGroupIds())) {
            out.add(new Violation(prefix + "modifierGroupIds", "owned", KitchenMessages.MODIFIER_GROUPS));
        }
    }

    /** De-duplicated, in Health Canada list order. */
    static List<String> allergens(List<String> codes) {
        var set = Set.copyOf(codes);
        return java.util.Arrays.stream(Allergen.values())
                .map(Allergen::code)
                .filter(set::contains)
                .toList();
    }

    static List<String> distinct(List<String> values) {
        return List.copyOf(new LinkedHashSet<>(values));
    }
}
