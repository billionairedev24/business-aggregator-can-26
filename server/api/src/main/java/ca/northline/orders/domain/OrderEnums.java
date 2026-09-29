package ca.northline.orders.domain;

import ca.northline.shared.CodedEnum;

/** Coded enums of {@code orders.orders} / {@code orders.order_lines} and the seller-facing status. */
public final class OrderEnums {
    private OrderEnums() {}

    /** {@code orders.state}. */
    public enum OrderState implements CodedEnum {
        PLACED,
        ACCEPTED,
        PACKING,
        READY,
        PICKED_UP,
        DELIVERED,
        CONFIRMED,
        REFUNDED,
        CANCELLED
    }

    /** {@code order_lines.state}. */
    public enum LineState implements CodedEnum {
        PENDING,
        PACKED,
        SHORT,
        REFUNDED
    }

    /**
     * What the seller sees for its share of an order: To pack → Awaiting pickup → Out for delivery → Delivered; Issue
     * when a line was short / refunded or the customer reported a problem.
     */
    public enum SellerStatus implements CodedEnum {
        TO_PACK,
        AWAITING_PICKUP,
        OUT_FOR_DELIVERY,
        DELIVERED,
        ISSUE,
        CANCELLED
    }
}
