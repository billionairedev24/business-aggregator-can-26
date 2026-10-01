package ca.northline.availability.application;

import ca.northline.availability.api.SlotHolds.Hold;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Outbound port: where slot holds live — Valkey/Redis in the cloud (atomic per member with a script), memory under
 * {@code local}/{@code test} (one instance). Expired holds are gone.
 */
public interface SlotHoldStore {

    /**
     * Places the hold unless another unexpired hold of the same member overlaps it, widened by {@code buffer} on both
     * sides. Atomic: of two customers racing for the same member's time, one wins.
     */
    boolean place(Hold hold, Duration buffer, Instant now);

    Optional<Hold> find(String holdId, Instant now);

    /** Unexpired holds of a member overlapping [from, to). */
    List<Hold> ofMember(String merchantId, String memberUserId, Instant from, Instant to, Instant now);

    /** The customer's unexpired holds with this business. */
    List<Hold> ofCustomer(String customerId, String merchantId, Instant now);

    void remove(String holdId);

    void attach(String holdId, String checkout);

    Optional<String> checkout(String holdId);
}
