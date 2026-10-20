package ca.northline.trust.api;

import java.time.Instant;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-152: provider-funded rewards pay out. When a completed sale earns points, the business's running reward (2× or 3×)
 * adds the extra points, paid from its budget at the redemption rate ({@code NORTHLINE_POINTS_PER_DOLLAR}: 100 = a point
 * costs $0.01). The budget is drawn down under a lock on the reward row and the reward stops when it runs out (a sale
 * that needs more than is left gets what is left). Payments then charges the business ({@code
 * payments.api.RewardCharges}). A refund of the sale takes back its share of the extra points and gives the budget and
 * the charge back.
 */
public interface RewardCredits {

    /**
     * @param soldAt when the money was held (the sale must be on or after the reward was switched on, and on or before
     *     its end date in the business's time zone)
     * @param listingId what was sold (a reward may apply to some listings only), null when unknown
     * @param basePoints the points the sale earns before Plus or the reward
     */
    record Sale(
            String merchantId,
            String escrowId,
            String customerId,
            Instant soldAt,
            @Nullable String listingId,
            long basePoints) {}

    /** @param exhausted the budget ran out with this credit */
    record Granted(String creditId, int multiplier, long points, long cents, boolean exhausted) {}

    /**
     * Credits the extra points to the customer's ledger ({@code reward}, funded by the business) once per escrow.
     *
     * @param expiresAt when they expire (the same as the sale's own points)
     */
    Optional<Granted> credit(Sale sale, @Nullable Instant expiresAt);

    record TakenBack(String creditId, String merchantId, long points, long cents) {}

    /**
     * A refund of {@code refundedCents} out of the sale's {@code saleCents}: takes back that share of the extra points
     * (once per {@code refundId}) and gives the budget back.
     */
    Optional<TakenBack> takeBack(String escrowId, String refundId, long refundedCents, long saleCents);
}
