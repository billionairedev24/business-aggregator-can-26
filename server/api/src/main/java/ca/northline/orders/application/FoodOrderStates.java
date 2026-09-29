package ca.northline.orders.application;

import java.time.Instant;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: moves a food order's state as the kitchen works on it (added by the kitchen workstream, see
 * {@link FoodOrderProgress}). {@code from} guards against replays and out-of-order delivery.
 */
public interface FoodOrderStates {

    /** Sets {@code state} (and {@code delivered_at} when given) if the order is food and currently in {@code from}. */
    void move(String orderId, Set<String> from, String state, @Nullable Instant deliveredAt);
}
