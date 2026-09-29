package ca.northline.food.domain;

import org.jspecify.annotations.Nullable;

/**
 * A modifier group's rule ("Pick exactly 1 · required", "Pick any · up to 4"). {@link #minSelect()} /
 * {@link #maxSelect()} are the machine-readable form stored next to it ({@code min_select}, {@code max_select};
 * null max = unlimited).
 */
public record ModifierRule(PickRule rule, int count, boolean required) {

    public int minSelect() {
        return switch (rule) {
            case EXACTLY, AT_LEAST -> required ? count : 0;
            case UP_TO -> required ? 1 : 0;
        };
    }

    public @Nullable Integer maxSelect() {
        return rule == PickRule.AT_LEAST ? null : count;
    }
}
