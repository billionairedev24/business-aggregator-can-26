package ca.northline.trust.application;

import ca.northline.trust.domain.MerchantReward;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/** Outbound port (S-75, S-152): {@code trust.merchant_rewards}, one row per business, and its credits. */
public interface RewardStore {

    Optional<MerchantReward> find(String merchantId);

    /** Locks the business's reward row for a draw on its budget. */
    Optional<MerchantReward> lock(String merchantId);

    void save(MerchantReward reward, String actorId);

    /** Adds to what the reward spent (marking it exhausted when nothing is left) — or gives back with a negative. */
    void spend(String merchantId, long cents, Instant at);

    /** A sale the reward paid extra points on. */
    record Credit(
            String id,
            String merchantId,
            String userId,
            String escrowId,
            int multiplier,
            long points,
            long cents,
            long reversedPoints,
            long reversedCents,
            Instant createdAt) {}

    /** @return false when the escrow already has a credit */
    boolean insert(Credit credit);

    Optional<Credit> creditOf(String escrowId);

    void reversed(String creditId, long points, long cents);

    /** Running rewards with budget left, for "rewards near you" (callers filter by place and date). */
    List<MerchantReward> running();

    /** What the business's reward credited, net of refunds: cents, points and on how many sales. */
    record Totals(long cents, long points, long sales) {}

    Totals totals(String merchantId);
}
