package ca.northline.orders.application;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: a customer's order and its lines for tracking (S-52). */
public interface TrackingStore {

    Optional<Header> order(String customerId, String orderId);

    List<LineState> lines(String orderId);

    record Header(
            String id,
            @Nullable String ref,
            String type,
            String state,
            Instant placedAt,
            long subtotalCents,
            long deliveryFeeCents,
            long taxCents,
            long tipCents,
            @Nullable String deliveryKind,
            @Nullable String fulfilmentMode,
            @Nullable String windowId,
            @Nullable Instant scheduledFor,
            @Nullable Instant deliveredAt) {}

    /** @param state {@code pending} | {@code packed} | {@code short} | {@code refunded} */
    record LineState(String merchantId, int qty, String state) {}
}
