package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: the deliveries orders handed over ({@code fulfilment.deliveries}) and their shops' packing. */
public interface DeliveryStore {

    /** Inserts the delivery unless the order already has one; true when inserted. */
    boolean insert(Delivery delivery);

    /** Adds a shop to collect from (no-op when it is there). */
    void addPickup(String orderId, String merchantId);

    void packed(String orderId, String merchantId, Instant at);

    void readyBy(String orderId, Instant readyBy);

    /** Pooled deliveries whose customers' cut-off has passed and that have no run yet. */
    List<Delivery> waitingPooled(Instant now);

    /** Direct deliveries without a run. */
    List<Delivery> waitingDirect();

    Optional<Delivery> find(String orderId);

    List<Delivery> onRun(String runId);

    /** The orders join the run ({@code planned}). */
    void attach(Collection<String> orderIds, String runId, Instant at);

    void moveState(String orderId, String state, Instant at);

    /** Age-restricted items: the recipient proves {@code age} with photo ID at the door. */
    void idCheck(String orderId, int age, @Nullable String province);

    /** The ID check a delivery needs, if any. */
    Optional<IdCheck> idCheck(String orderId);

    record IdCheck(int age, @Nullable String province) {}

    /** Clears drop-off addresses of deliveries that ended before {@code before}; returns how many. */
    int forgetAddresses(Instant before);

    /**
     * @param state {@code waiting} | {@code planned} | {@code picked_up} | {@code delivered} | {@code cancelled}
     * @param pickups the shops to collect from, with their packing time
     */
    record Delivery(
            String orderId,
            @Nullable String orderRef,
            String orderType,
            String kind,
            String market,
            @Nullable String windowId,
            @Nullable String windowLabel,
            @Nullable Instant startsAt,
            @Nullable Instant endsAt,
            @Nullable Instant orderBy,
            @Nullable Instant packBy,
            @Nullable Instant readyBy,
            @Nullable String customerId,
            @Nullable Dropoff dropoff,
            String pin,
            String state,
            @Nullable String runId,
            List<Pickup> pickups) {

        public Delivery {
            pickups = List.copyOf(pickups);
        }

        public boolean allPacked() {
            return !pickups.isEmpty() && pickups.stream().allMatch(p -> p.packedAt() != null);
        }
    }

    record Pickup(String merchantId, @Nullable Instant packedAt) {}
}
