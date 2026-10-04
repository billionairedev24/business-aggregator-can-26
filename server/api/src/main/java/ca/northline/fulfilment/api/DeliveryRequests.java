package ca.northline.fulfilment.api;

import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Inbound port: the orders module hands over every order that needs a courier (S-86), so fulfilment plans runs from
 * its own rows and never reads orders' tables (orders depends on fulfilment, not the other way round). Every method is
 * idempotent: they are called from retried event listeners.
 */
public interface DeliveryRequests {

    /**
     * An order needs a courier. A repeat for the same order adds its pickups (S-51 publishes {@code order.placed} once
     * per shop) and changes nothing else.
     */
    void request(Request request);

    /** A shop finished packing its lines of the order; the courier may pick them up. */
    void packed(String orderId, String merchantId, Instant at);

    /** Food: the kitchen accepted and expects the order ready at {@code readyBy} — the courier is sent to arrive then. */
    void readyBy(String orderId, Instant readyBy);

    /**
     * @param orderType {@code goods} | {@code food}
     * @param kind {@code pooled} (a run's window) | {@code direct} (its own courier: the direct goods courier, a hot food
     *     delivery)
     * @param market the market's display name (region model), e.g. the city the run serves
     * @param window the pooled run's window, null for {@code direct}
     * @param pickups the shops / the kitchen the courier collects from
     * @param dropoff where it goes (personal data: kept for the courier until 30 days after delivery)
     * @param readyBy food: when the kitchen expects it ready, when known
     * @param idCheckAge age-restricted items (2026-10-04): the age the recipient proves with photo ID at the door;
     *     null for an order without them
     * @param idCheckProvince the province whose rules set {@code idCheckAge}
     */
    record Request(
            String orderId,
            @Nullable String orderRef,
            String orderType,
            String kind,
            String market,
            @Nullable Window window,
            List<String> pickups,
            Dropoff dropoff,
            @Nullable String customerId,
            @Nullable Instant readyBy,
            @Nullable Integer idCheckAge,
            @Nullable String idCheckProvince) {

        public Request {
            pickups = List.copyOf(pickups);
        }

        /** An order without age-restricted items. */
        public Request(
                String orderId,
                @Nullable String orderRef,
                String orderType,
                String kind,
                String market,
                @Nullable Window window,
                List<String> pickups,
                Dropoff dropoff,
                @Nullable String customerId,
                @Nullable Instant readyBy) {
            this(orderId, orderRef, orderType, kind, market, window, pickups, dropoff, customerId, readyBy, null, null);
        }
    }

    /**
     * A pooled run's window ({@code orders.delivery_windows}).
     *
     * @param orderBy the customers' cut-off: no order joins the run after it, so the run is planned then
     * @param packBy the shops' cut-off (the Studio's "Pack by")
     */
    record Window(
            String windowId,
            @Nullable String label,
            Instant startsAt,
            Instant endsAt,
            Instant orderBy,
            Instant packBy) {}

    record Dropoff(
            String street,
            @Nullable String unit,
            @Nullable String city,
            @Nullable String postal,
            @Nullable String note,
            @Nullable Double lat,
            @Nullable Double lng) {}
}
