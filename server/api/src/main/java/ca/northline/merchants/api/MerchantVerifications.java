package ca.northline.merchants.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Read-only view of a business's verifications ({@code merchants.verifications}) for other modules (S-37): the
 * catalogue's licence check for regulated categories and the kitchen's food-safety evidence.
 */
public interface MerchantVerifications {

    /**
     * A verified licence or registry check for {@code registry} (case-insensitive) that has not expired by
     * {@code at}.
     */
    boolean hasVerifiedLicence(String merchantId, String registry, Instant at);

    /** The best row of one check type: a verified one first, then the most recently updated. */
    Optional<Evidence> latest(String merchantId, String checkType);

    record Evidence(
            @Nullable String reference,
            @Nullable String status,
            @Nullable Instant expiresAt) {}
}
