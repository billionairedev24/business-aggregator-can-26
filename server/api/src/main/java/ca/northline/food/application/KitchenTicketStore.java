package ca.northline.food.application;

import ca.northline.food.domain.KitchenStage;
import ca.northline.food.domain.KitchenTicket;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the kitchen's tickets ({@code food.kitchen_tickets}) over the food orders that have lines of this
 * kitchen (the orders module's {@code KitchenOrderFeed}) and their courier legs (fulfilment's {@code CourierPickups}).
 */
public interface KitchenTicketStore {

    /** Open food orders of the kitchen (not handed off, not cancelled), due within the next hour when scheduled. */
    List<LiveOrderRow> open(String merchantId, Instant now);

    Optional<KitchenTicket> ticket(String merchantId, String orderId);

    /** Merchant's share of the order (Σ qty × unit) and the slowest line's extra prep. */
    OrderLoad load(String merchantId, String orderId);

    void save(KitchenTicket ticket);

    record OrderLoad(long cents, int slowestItemAddMin) {}

    record LineRow(int qty, String title, List<String> modifiers) {}

    /**
     * @param customerId the orderer (group host for group orders)
     * @param groupSize people in a group order (host included), 0 when not a group order
     * @param courierUserId the courier's user id once one is assigned (delivery)
     * @param courierEta when the courier arrives at the kitchen
     * @param courierArrivedAt the courier is waiting at the kitchen
     */
    record LiveOrderRow(
            String orderId,
            @Nullable String ref,
            @Nullable String customerId,
            int groupSize,
            Instant placedAt,
            @Nullable Instant scheduledFor,
            String fulfilmentMode,
            @Nullable Instant customerEta,
            KitchenStage stage,
            @Nullable Instant readyBy,
            List<LineRow> lines,
            boolean courierAssigned,
            @Nullable String courierUserId,
            @Nullable Instant courierEta,
            @Nullable Instant courierArrivedAt,
            @Nullable Integer idCheckAge) {}
}
