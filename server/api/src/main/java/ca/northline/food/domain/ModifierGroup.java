package ca.northline.food.domain;

import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.Builder;
import org.jspecify.annotations.Nullable;

/**
 * A reusable option group (Size, Noodles, Extras…) with its rule, options and nesting: {@code showForOptionIds} = show
 * the group only when one of those options (of another group) is chosen, e.g. "Extra noodles" only for Large.
 */
@Builder(toBuilder = true)
public record ModifierGroup(
        String id,
        String merchantId,
        String name,
        ModifierRule rule,
        List<String> showForOptionIds,
        List<Option> options,
        int sort) {

    public ModifierGroup {
        showForOptionIds = List.copyOf(showForOptionIds);
        options = List.copyOf(options);
    }

    public record Option(
            @Nullable String id, String name, long priceDeltaCents, boolean isDefault, boolean soldOut, int sort) {}

    /**
     * Rules Bean Validation can't see: enough options for the rule, and nesting only on options of other groups of the
     * same kitchen ({@code otherGroupsOptionIds}).
     */
    public ModifierGroup validated(Set<String> otherGroupsOptionIds) {
        var errors = new ArrayList<Violation>();
        if (options.isEmpty()) {
            errors.add(new Violation("options", "required", KitchenMessages.OPTIONS_MIN));
        } else if (rule.rule() != PickRule.UP_TO && rule.count() > options.size()) {
            errors.add(new Violation("pickCount", "range", KitchenMessages.PICK_MORE_THAN_OPTIONS));
        }
        if (!otherGroupsOptionIds.containsAll(showForOptionIds)) {
            errors.add(new Violation("showForOptionIds", "owned", KitchenMessages.NESTED_OPTIONS));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return this;
    }
}
