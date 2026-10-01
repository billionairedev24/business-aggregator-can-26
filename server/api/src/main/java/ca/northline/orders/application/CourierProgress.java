package ca.northline.orders.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * The courier part of a customer's tracking (S-88): who is bringing it, where they are, when they'll be at the door,
 * and the drop-off PIN the customer can give them. From fulfilment's {@code DeliveryStatuses} and
 * {@code CourierLocations}; the position only while the order is on its way.
 *
 * @param state the delivery's progress: waiting, planned, picked_up, delivered, cancelled
 * @param courierName the courier's first name, once assigned
 * @param eta when the courier is expected at the door: live from the position while on the way, else planned
 * @param stopsBefore drop-offs left before this one
 * @param lat the courier's latest position (null when not on the way or not reported in the last minutes)
 * @param pin the 4 digits the courier may ask for at the door; null once delivered
 */
public record CourierProgress(
        String state,
        @Nullable String runLabel,
        @Nullable String courierName,
        @Nullable Instant eta,
        int stopsBefore,
        @Nullable Double lat,
        @Nullable Double lng,
        @Nullable Instant positionAt,
        @Nullable String pin) {}
