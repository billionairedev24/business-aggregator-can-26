package ca.northline.merchants.application;

import ca.northline.merchants.api.SellerDirectory.Check;
import ca.northline.merchants.api.SellerDirectory.Oversight;
import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port of {@link SellerOversight}: the business's status, tier and checks, and the oversight trail. */
public interface OversightStore {

    /** Locks the business row for the change; empty when unknown. */
    Optional<State> lock(String merchantId);

    void status(String merchantId, String status, Instant at);

    void tier(String merchantId, String tier, Instant at);

    Optional<Check> check(String merchantId, String verificationId);

    /** The check is due again: {@code expired}, expiring now (the business re-uploads it; compliance shows it due). */
    void expire(String verificationId, Instant at);

    void insert(Oversight action, String merchantId);

    record State(
            String id, @Nullable String status, @Nullable String tier) {}
}
