package ca.northline.orders.domain;

import java.util.Set;

/**
 * When an order may be marked delivered or confirmed (S-78). The courier's drop-off delivers an order that is still on
 * its way (any state before {@code delivered}); the customer confirms one the courier has picked up or delivered —
 * confirming first also counts as the delivery. Replays and late events change nothing.
 */
public final class OrderDelivery {
    private OrderDelivery() {}

    public static final String NOT_DELIVERED = "Your order hasn't been delivered yet.";
    public static final String CLOSED = "This order was cancelled or refunded.";

    private static final Set<String> ON_ITS_WAY = Set.of("placed", "accepted", "packing", "ready", "picked_up");
    private static final Set<String> CONFIRMABLE = Set.of("picked_up", "delivered");
    private static final Set<String> CLOSED_STATES = Set.of("cancelled", "refunded");

    /** The courier's drop-off moves the order to {@code delivered}. */
    public static boolean deliverable(String state) {
        return ON_ITS_WAY.contains(state);
    }

    /** The customer can confirm receipt. */
    public static boolean confirmable(String state) {
        return CONFIRMABLE.contains(state);
    }

    public static boolean closed(String state) {
        return CLOSED_STATES.contains(state);
    }
}
