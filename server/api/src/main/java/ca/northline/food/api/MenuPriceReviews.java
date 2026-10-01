package ca.northline.food.api;

import ca.northline.shared.MerchantScope;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * S-92: dishes held by the S-67 price check (more than ±40 % off the median of comparable dishes, price not
 * confirmed) for the console's listing vetting queue, and the reviewer's decision: approve = the price is kept (as if
 * the owner confirmed it), reject = the dish goes back to draft and the owners are told why. Audit-logged.
 */
public interface MenuPriceReviews {

    List<HeldDish> held(MerchantScope scope, int limit);

    /** @param reasons {@code prohibited | misleading | pricing | licence | images | other}; required to reject */
    HeldDish decide(String itemId, boolean approve, List<String> reasons, @Nullable String note, String staffId, String role);

    /**
     * @param deviationPct how far from the median, in whole percent (+52 = above, −45 = below)
     * @param state {@code held} while waiting, {@code approved} / {@code rejected} once decided
     */
    record HeldDish(
            String itemId,
            String merchantId,
            String name,
            long priceCents,
            @Nullable Long medianCents,
            int deviationPct,
            @Nullable Instant since,
            String state) {}
}
