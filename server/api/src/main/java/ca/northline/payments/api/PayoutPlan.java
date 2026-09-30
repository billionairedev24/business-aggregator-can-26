package ca.northline.payments.api;

import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * The merchant's payout schedule as Northline runs it (Stripe's own schedule is always {@code manual}), for screens
 * outside payments such as Settings › Stripe &amp; compliance.
 */
public interface PayoutPlan {

    /**
     * @param interval {@code daily} | {@code weekly} | {@code monthly} | {@code manual}
     * @param weekday {@code monday} … {@code friday} for weekly payouts
     */
    record Plan(String interval, @Nullable String weekday, boolean instantPayouts) {}

    /** Empty when the merchant has no connected account yet. */
    Optional<Plan> of(String merchantId);
}
