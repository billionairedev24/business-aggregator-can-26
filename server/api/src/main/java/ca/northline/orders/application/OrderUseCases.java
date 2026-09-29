package ca.northline.orders.application;

import ca.northline.orders.application.OrderViews.OrderBoard;
import ca.northline.orders.application.OrderViews.OrderSummary;

/** Orders use cases (seller side). */
public final class OrderUseCases {
    private OrderUseCases() {}

    /** The packing list: open orders plus today's deliveries and issues, with the chip counts. */
    public interface ListOrders {
        OrderBoard board(String merchantId);
    }

    public interface ViewOrder {
        OrderSummary view(String merchantId, String orderId);
    }

    /** "Mark packed" (state → Awaiting pickup). Publishes {@code order.packed}. */
    public interface PackOrder {
        OrderSummary pack(String merchantId, String orderId, String actorId);
    }
}
