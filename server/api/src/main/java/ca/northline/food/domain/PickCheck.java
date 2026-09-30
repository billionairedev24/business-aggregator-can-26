package ca.northline.food.domain;

import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * The customer's choices on one dish against its modifier groups (S-57) — the kitchen's pick rules: exactly N (0 or N
 * when optional), at least N (none when optional), up to N. A nested group counts only when one of the options it
 * hangs off ({@code showForOptionIds}) is chosen. Messages are the customer's, one per group.
 */
public final class PickCheck {
    private PickCheck() {}

    public record Option(String id, String name, boolean soldOut) {}

    public record Group(
            String id, String name, PickRule rule, int count, boolean required, Set<String> showForOptionIds, List<Option> options) {
        public Group {
            showForOptionIds = Set.copyOf(showForOptionIds);
            options = List.copyOf(options);
        }
    }

    /** One violation per broken group under {@code field} (e.g. {@code items[0].optionIds}); empty = fine. */
    public static List<Violation> check(String field, List<Group> groups, Collection<String> chosen) {
        var out = new ArrayList<Violation>();
        var picked = new HashSet<>(chosen);
        if (picked.size() != chosen.size()) {
            out.add(new Violation(field, "duplicate", FoodOrderMessages.PICK_TWICE));
            return out;
        }
        var visible = groups.stream()
                .filter(g -> g.showForOptionIds().isEmpty() || g.showForOptionIds().stream().anyMatch(picked::contains))
                .toList();
        var known = new HashSet<String>();
        visible.forEach(g -> g.options().forEach(o -> known.add(o.id())));
        if (!known.containsAll(picked)) {
            out.add(new Violation(field, "unknown", FoodOrderMessages.PICK_UNKNOWN));
            return out;
        }
        for (var g : visible) {
            var n = (int) g.options().stream().filter(o -> picked.contains(o.id())).count();
            var ok = switch (g.rule()) {
                case EXACTLY -> n == g.count() || (!g.required() && n == 0);
                case AT_LEAST -> n >= g.count() || (!g.required() && n == 0);
                case UP_TO -> n <= g.count() && (!g.required() || n >= 1);
            };
            if (!ok) {
                var message = switch (g.rule()) {
                    case EXACTLY -> FoodOrderMessages.pickExactly(g.count(), g.name());
                    case AT_LEAST -> FoodOrderMessages.pickAtLeast(g.count(), g.name());
                    case UP_TO -> FoodOrderMessages.pickUpTo(g.count(), g.name());
                };
                out.add(new Violation(field, "pick_rule", message));
            }
            g.options().stream()
                    .filter(o -> o.soldOut() && picked.contains(o.id()))
                    .forEach(o -> out.add(new Violation(field, "sold_out", FoodOrderMessages.soldOut(o.name()))));
        }
        return out;
    }
}
