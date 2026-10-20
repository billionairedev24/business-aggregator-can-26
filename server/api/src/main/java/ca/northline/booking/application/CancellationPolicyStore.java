package ca.northline.booking.application;

import ca.northline.booking.api.CancellationPolicies.Policy;
import java.time.Instant;
import java.util.Optional;

/** Outbound port (S-153): {@code booking.cancellation_policies}. */
public interface CancellationPolicyStore {

    Optional<Policy> find(String merchantId);

    void save(String merchantId, Policy policy, String actorId, Instant at);

    void delete(String merchantId);
}
