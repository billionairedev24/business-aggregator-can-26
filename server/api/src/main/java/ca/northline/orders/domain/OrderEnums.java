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
        CANCELLED,
        /** Age-restricted items nobody of age could take (2026-10-04). */
        RETURNED,
        /** S-151: the courier couldn't hand it over; the customer chooses re-delivery or a refund. */
        DELIVERY_FAILED
    }

    /** {@code order_lines.state}. */
    public enum LineState implements CodedEnum {
        PENDING,
        PACKED,
        SHORT,
        REFUNDED,
        /** S-151: cancelled by the customer, the shop or Northline (its money was given back). */
        CANCELLED
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
