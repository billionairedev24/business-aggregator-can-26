package ca.northline.payments.application;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/** Outbound port (S-152): {@code payments.reward_charges}, one row per reference. */
public interface RewardChargeStore {

    /** @return false when {@code ref} was already recorded */
    boolean insert(String id, String ref, String kind, String merchantId, String escrowId, long cents, Instant at);

    /** The Stripe transfer or reversal that moved the money. */
    void stripeObject(String id, @Nullable String stripeId);
}
