package ca.northline.food.application;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** What the consumer site shows of kitchens (S-57), serialized as is under {@code /api/v1/public/kitchens}. */
public final class PublicKitchenViews {
    private PublicKitchenViews() {}

    /**
     * A kitchen on the food landing (design 06 {@code food}): open now or when it opens, how long, how far, the fee.
     *
     * @param delivers the visitor's point is inside the kitchen's delivery radius; null when a point is unknown
     * @param deliveryFeeCents the direct courier's fee to the visitor ($2.99 while the distance isn't known)
     */
    public record Card(
            String merchantId,
            @Nullable String slug,
            String name,
            List<String> cuisines,
            List<String> dietary,
            String priceLevel,
            boolean open,
            @Nullable Instant opensAt,
            @Nullable Instant closesAt,
            boolean paused,
            List<String> fulfilment,
            int prepMin,
            int etaFromMin,
            int etaToMin,
            int pickupFromMin,
            int pickupToMin,
            @Nullable Double distanceKm,
            @Nullable Boolean delivers,
            long deliveryFeeCents,
            double rating,
            int reviews,
            @Nullable String brandColor) {
        public Card {
            cuisines = List.copyOf(cuisines);
            dietary = List.copyOf(dietary);
            fulfilment = List.copyOf(fulfilment);
        }
    }

    public record Kitchens(String city, List<Card> items) {
        public Kitchens {
            items = List.copyOf(items);
        }
    }

    /**
     * The restaurant page (design 06 {@code restaurant}): the card, the kitchen's address, food-safety, minimum, the
     * scheduled-order windows, its live menus' sections with dishes, and the combos on offer.
     */
    public record Restaurant(
            Card kitchen,
            @Nullable String address,
            @Nullable String province,
            boolean ahsVerified,
            long minOrderCents,
            int serviceFeeBps,
            List<Instant> slots,
            List<Section> sections,
            List<Combo> combos) {
        public Restaurant {
            slots = List.copyOf(slots);
            sections = List.copyOf(sections);
            combos = List.copyOf(combos);
        }
    }

    /** @param menu the menu's name (a kitchen may have several live menus, e.g. dinner and lunch) */
    public record Section(String id, String name, String menu, List<Dish> items) {
        public Section {
            items = List.copyOf(items);
        }
    }

    /**
     * @param availableNow orderable now (not sold out, inside its own and its menu's window)
     * @param allergens Health Canada codes ({@code peanuts}, {@code shellfish} …)
     */
    public record Dish(
            String id,
            String name,
            @Nullable String description,
            long priceCents,
            List<String> dietary,
            List<String> allergens,
            boolean soldOut,
            boolean availableNow,
            String availability,
            List<Group> groups) {
        public Dish {
            dietary = List.copyOf(dietary);
            allergens = List.copyOf(allergens);
            groups = List.copyOf(groups);
        }
    }

    /**
     * A modifier group: {@code rule} exactly | at_least | up_to with {@code count}; {@code showForOptionIds} = shown only
     * when one of those options is picked.
     */
    public record Group(
            String id,
            String name,
            String rule,
            int count,
            boolean required,
            List<String> showForOptionIds,
            List<Option> options) {
        public Group {
            showForOptionIds = List.copyOf(showForOptionIds);
            options = List.copyOf(options);
        }
    }

    public record Option(String id, String name, long deltaCents, boolean isDefault, boolean soldOut) {}

    /**
     * @param fromCents what the combo costs with the cheapest dish in every slot
     * @param saveCents against those dishes bought separately
     */
    public record Combo(
            String id,
            String name,
            String pricing,
            @Nullable Long priceCents,
            @Nullable Integer discountBps,
            long fromCents,
            long saveCents,
            boolean availableNow,
            List<Slot> slots) {
        public Combo {
            slots = List.copyOf(slots);
        }
    }

    /** "Any 2 mains": {@code qty} dishes out of {@code itemIds} (the section's, or the listed ones). */
    public record Slot(String label, int qty, List<String> itemIds) {
        public Slot {
            itemIds = List.copyOf(itemIds);
        }
    }
}
