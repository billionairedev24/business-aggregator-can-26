package ca.northline.food.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The food orders a kitchen cooks, read from the orders module (S-64). The orders module owns orders, order lines and
 * group orders and implements this; it is declared here because orders already depends on food (it prices dishes with
 * {@link FoodMenuPricing} and moves order state on the kitchen events), so food can't call {@code orders.api} without
 * a module cycle (BACKEND_CONVENTIONS § 2, the {@code payments.api.MerchantBillingFacts} pattern).
 */
public interface KitchenOrderFeed {

    /** Food orders in {@code placed, accepted, packing, ready} with a line of this kitchen, due by {@code dueBy}. */
    List<FoodOrder> open(String merchantId, Instant dueBy);

    /** One food order with a line of this kitchen, in any state. */
    Optional<FoodOrder> order(String merchantId, String orderId);

    /** This kitchen's lines of the orders, by order then line id. */
    List<Line> lines(String merchantId, Collection<String> orderIds);

    /**
     * @param customerId the orderer (the host for a group order)
     * @param groupSize people in a group order (host included), 0 when not a group order
     * @param fulfilmentMode delivery | pickup
     * @param state the order's state (placed, accepted, packing, ready, delivered, cancelled, refunded …)
     * @param idCheckAge age-restricted dishes: the age the recipient proves with photo ID at handoff, else null
     */
    record FoodOrder(
            String id,
            @Nullable String ref,
            @Nullable String customerId,
            int groupSize,
            Instant placedAt,
            @Nullable Instant scheduledFor,
            String fulfilmentMode,
            @Nullable Instant customerEta,
            String state,
            @Nullable Integer idCheckAge) {}

    /**
     * @param title the line's title at checkout, when it kept one
     * @param modifiers the chosen modifier names
     */
    record Line(
            String id,
            String orderId,
            int qty,
            long unitCents,
            @Nullable String menuItemId,
            @Nullable String title,
            List<String> modifiers) {

        public Line {
            modifiers = List.copyOf(modifiers);
        }
    }
}
