package ca.northline.food.application;

import ca.northline.food.domain.PickRule;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Modifiers &amp; combos → Modifier groups. */
public final class ModifierUseCases {
    private ModifierUseCases() {}

    public interface ListModifierGroups {
        List<GroupView> groups(String merchantId);
    }

    /** "New group", edit, "+ option", delete (detaches it from items). */
    public interface EditModifierGroups {
        GroupView create(String merchantId, GroupCommand command);

        GroupView update(String merchantId, String groupId, GroupCommand command);

        GroupView addOption(String merchantId, String groupId, OptionCommand option);

        void delete(String merchantId, String groupId);
    }

    /** An option; {@code id} null = new. */
    public record OptionCommand(
            @Nullable String id, String name, long priceDeltaCents, boolean isDefault, boolean soldOut) {}

    public record GroupCommand(
            String name,
            PickRule pickRule,
            int pickCount,
            boolean required,
            List<String> showForOptionIds,
            List<OptionCommand> options) {}

    public record OptionView(String id, String name, long priceDeltaCents, boolean isDefault, boolean soldOut) {}

    /**
     * {@code minSelect} / {@code maxSelect} (null = no max) are the rule in numbers; {@code usedBy} = items using the
     * group ("Used by 3 items"). {@code GET …/modifier-groups} is also the onboarding contract ({@code [{id, name}]}).
     */
    public record GroupView(
            String id,
            String name,
            PickRule pickRule,
            int pickCount,
            boolean required,
            int minSelect,
            @Nullable Integer maxSelect,
            List<String> showForOptionIds,
            List<OptionView> options,
            int usedBy) {}
}
