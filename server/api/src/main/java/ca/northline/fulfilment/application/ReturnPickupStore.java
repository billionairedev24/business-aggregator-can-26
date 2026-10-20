package ca.northline.fulfilment.application;

import ca.northline.fulfilment.api.DeliveryRequests.Dropoff;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-151): returns the shops approved for pickup ({@code fulfilment.return_pickups}). */
public interface ReturnPickupStore {

    /** Inserts unless the return has one; true when inserted. */
    boolean insert(ReturnPickup pickup);

    Optional<ReturnPickup> find(String returnId);

    /** Waiting for a run, oldest first. */
    List<ReturnPickup> waiting();

    /** The pickups planned on a run. */
    List<ReturnPickup> onRun(String runId);

    /** The pickup joins the run ({@code planned}). */
    void attach(String returnId, String runId, Instant at);

    /** Moves the pickup on: to collected (at the door), returned (back at the shop) or cancelled. */
    void moveState(String returnId, String state, Instant at);

    /** Clears the collection addresses of pickups that ended before {@code before}; returns how many. */
    int forgetAddresses(Instant before);

    /** A return pickup; its state is waiting, planned, collected, returned (back at the shop) or cancelled. */
    record ReturnPickup(
            String returnId,
            @Nullable String returnRef,
            String orderId,
            String merchantId,
            String market,
            @Nullable String customerId,
            @Nullable Dropoff collectFrom,
            String state,
            @Nullable String runId,
            Instant createdAt) {}
}
