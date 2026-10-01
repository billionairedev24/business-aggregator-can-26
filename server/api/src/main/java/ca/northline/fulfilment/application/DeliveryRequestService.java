package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryRequests;
import ca.northline.fulfilment.application.DeliveryStore.Delivery;
import ca.northline.fulfilment.domain.DeliveryRules;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link DeliveryRequests}: the orders module's hand-over, stored as {@code fulfilment.deliveries} (S-86). */
@Service
@RequiredArgsConstructor
@Transactional
class DeliveryRequestService implements DeliveryRequests {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final DeliveryStore deliveries;

    @Override
    public void request(Request r) {
        if (!r.kind().equals("pooled") && !r.kind().equals("direct")) {
            throw new IllegalArgumentException("delivery kind " + r.kind());
        }
        if (r.kind().equals("pooled") && r.window() == null) {
            throw new IllegalArgumentException("a pooled delivery needs its window: " + r.orderId());
        }
        var w = r.window();
        deliveries.insert(new Delivery(
                r.orderId(),
                r.orderRef(),
                r.orderType(),
                r.kind(),
                r.market(),
                w == null ? null : w.windowId(),
                w == null ? null : w.label(),
                w == null ? null : w.startsAt(),
                w == null ? null : w.endsAt(),
                w == null ? null : w.orderBy(),
                w == null ? null : w.packBy(),
                r.readyBy(),
                r.customerId(),
                r.dropoff(),
                DeliveryRules.pin(RANDOM),
                "waiting",
                null,
                List.of()));
        r.pickups().forEach(m -> deliveries.addPickup(r.orderId(), m));
    }

    @Override
    public void packed(String orderId, String merchantId, Instant at) {
        deliveries.packed(orderId, merchantId, at);
    }

    @Override
    public void readyBy(String orderId, Instant readyBy) {
        deliveries.readyBy(orderId, readyBy);
    }
}
