package ca.northline.food.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Prices a food order from the kitchen's live menu (S-57 checkout, orders module): each dish with the customer's
 * modifier choices checked against the pick rules, and combos with a dish per slot. Throws {@code RuleViolation} (422,
 * fields {@code items[i].…} / {@code combos[i].…}) for broken choices and {@code Conflict} {@code item_unavailable}
 * (409) when a dish, option or combo can't be ordered for {@code at} (sold out, outside its window, menu hidden).
 */
public interface FoodMenuPricing {

    Priced price(Request request);

    /** @param at when the kitchen cooks it: now, or a scheduled order's window */
    record Request(String merchantId, Instant at, List<ItemLine> items, List<ComboLine> combos) {
        public Request {
            items = List.copyOf(items);
            combos = List.copyOf(combos);
        }
    }

    /** @param note special instructions ("sauce on the side"), ≤ 140 characters */
    record ItemLine(String itemId, int qty, List<String> optionIds, @Nullable String note) {
        public ItemLine {
            optionIds = List.copyOf(optionIds);
        }
    }

    /** @param itemIds one dish per unit of every slot, in slot order ("Any 2 mains" = two ids) */
    record ComboLine(String comboId, int qty, List<String> itemIds) {
        public ComboLine {
            itemIds = List.copyOf(itemIds);
        }
    }

    /**
     * @param subtotalCents the dishes, what the kitchen earns on (before tax)
     * @param prepAddMin the slowest dish's extra prep
     */
    record Priced(List<Line> lines, long subtotalCents, int prepAddMin) {
        public Priced {
            lines = List.copyOf(lines);
        }
    }

    /** One order line: a dish ({@code itemId}) or a combo ({@code comboId}); {@code unitCents} includes the choices. */
    record Line(
            @Nullable String itemId,
            @Nullable String comboId,
            String title,
            int qty,
            long unitCents,
            List<Choice> choices,
            @Nullable String note) {
        public Line {
            choices = List.copyOf(choices);
        }

        public long totalCents() {
            return unitCents * qty;
        }
    }

    /** A chosen option ("Large", +300) or, in a combo, a chosen dish ({@code group} = the slot's label). */
    record Choice(@Nullable String groupId, @Nullable String group, @Nullable String optionId, String name, long deltaCents) {}
}
