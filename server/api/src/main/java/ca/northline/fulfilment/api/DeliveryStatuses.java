package ca.northline.fulfilment.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Where an order's delivery is (S-86), for the customer's tracking (S-52/S-57, S-88) and the console's orders monitor.
 * Ids, times and the run code only; the courier's name is looked up by the caller (identity).
 */
public interface DeliveryStatuses {

    Optional<Status> of(String orderId);

    /**
     * @param state the delivery's progress: waiting (no run yet), planned, picked_up, delivered or cancelled
     * @param runState {@code planned} | {@code loading} | {@code en_route} | {@code done}, null without a run
     * @param courierUserId the assigned courier's user id
     * @param dropoffEta when the courier is expected at the customer's door
     * @param stopsBefore stops left on the run before this order's drop-off
     * @param pin the drop-off PIN the customer may give the courier (shown to the customer only)
     */
    record Status(
            String orderId,
            String state,
            @Nullable String runId,
            @Nullable String runLabel,
            @Nullable String runState,
            @Nullable String courierUserId,
            @Nullable Instant dropoffEta,
            int stopsBefore,
            @Nullable Instant deliveredAt,
            @Nullable String proofKind,
            String pin) {}
}
