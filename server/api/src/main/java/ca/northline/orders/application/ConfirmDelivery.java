package ca.northline.orders.application;

import ca.northline.orders.application.TrackOrder.OrderTracking;

/**
 * The customer confirms they received their order (S-78, design 06 "Delivered · you confirm, shops paid"): goods
 * escrow releases at once instead of 7 days after delivery. Only the order's customer (404 otherwise), only once the
 * courier picked it up; confirming again changes nothing.
 */
public interface ConfirmDelivery {

    OrderTracking confirm(String customerId, String orderId);
}
