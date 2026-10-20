package ca.northline.orders.application;

import ca.northline.orders.domain.AfterSale.CancelUntil;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.orders.after-sale} (S-151): who may cancel until when, the failed-delivery choice, and returns.
 * Defaults are ours (DECISIONS "S-151").
 *
 * @param customerCancelUntil the customer cancels while no shop has packed ({@code packed})
 * @param sellerCancelUntil a shop cancels its own lines until the courier picks them up ({@code picked_up})
 * @param staffCancelUntil Northline staff cancel until delivery ({@code delivered})
 * @param failedDeliveryDecideWithin how long the customer has to choose re-delivery or a refund after a failed
 *     drop-off; then the order is refunded
 * @param failedDeliveryFeeCents kept from the delivery fee (tax included) when a failed delivery is refunded; never
 *     more than the fee
 * @param returnDays the return window after delivery where neither the category nor its group sets one
 */
@ConfigurationProperties("northline.orders.after-sale")
public record AfterSaleProperties(
        @DefaultValue("packed") CancelUntil customerCancelUntil,
        @DefaultValue("picked_up") CancelUntil sellerCancelUntil,
        @DefaultValue("delivered") CancelUntil staffCancelUntil,
        @DefaultValue("48h") Duration failedDeliveryDecideWithin,
        @DefaultValue("0") long failedDeliveryFeeCents,
        @DefaultValue("30") int returnDays) {}
