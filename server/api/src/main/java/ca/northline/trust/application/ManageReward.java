package ca.northline.trust.application;

import ca.northline.trust.domain.MerchantReward;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * S-75: the Studio page's "Provider-funded reward" field — read it, switch it on (with its terms) or off. S-152: its
 * budget, the listings it applies to and what it has paid out.
 */
public interface ManageReward {

    Optional<MerchantReward> of(String merchantId);

    MerchantReward save(Command command);

    /** What the reward has paid for so far, net of refunds. */
    RewardStore.Totals totals(String merchantId);

    /**
     * @param budgetCents null keeps the stored budget
     * @param listingIds null keeps the stored list; empty = everything
     */
    record Command(
            String merchantId,
            String actorId,
            String role,
            boolean active,
            int multiplier,
            @Nullable String label,
            @Nullable LocalDate endsOn,
            @Nullable Long budgetCents,
            @Nullable List<String> listingIds) {}
}
