package ca.northline.booking.api;

import java.time.Duration;
import java.time.Instant;

/**
 * S-153: a provider's late-cancellation policy as customers see it before booking — the business's own (Studio ›
 * Availability) within its region's limits, else the region's default ({@code region.api.CancellationRules}). A
 * booking keeps the policy it was made under.
 */
public interface CancellationPolicies {

    /**
     * @param windowHours cancelling less than this before the start costs the fee (0 = always free)
     * @param feeBps the fee, in basis points of the booking's price (0 = none)
     */
    record Policy(int windowHours, int feeBps) {

        /** Free cancellation until then (the start itself with a window of 0). */
        public Instant freeUntil(Instant startsAt) {
            return startsAt.minus(Duration.ofHours(windowHours));
        }

        public boolean charges() {
            return feeBps > 0 && windowHours > 0;
        }
    }

    Policy of(String merchantId);
}
