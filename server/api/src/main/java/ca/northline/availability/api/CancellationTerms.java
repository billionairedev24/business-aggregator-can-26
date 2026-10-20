package ca.northline.availability.api;

import java.time.Duration;
import java.time.Instant;

/**
 * S-153: a provider's late-cancellation policy as customers see it before booking — its Availability › Booking rules
 * ("Customer reschedules free until", "Late-cancellation fee") within its region's limits
 * ({@code region.api.CancellationRules}), else the region's default for a business that never saved its rules. A
 * booking keeps the terms it was made under.
 */
public interface CancellationTerms {

    /**
     * @param windowHours cancelling less than this before the start costs the fee (0 = always free)
     * @param feeBps the fee as a share of the booking's price, in basis points (0 = none)
     * @param feeCents a fixed fee instead (0 = none); capped by the region's maximum share when charged
     * @param maxFeeBps the region's highest share, which also caps a fixed fee
     */
    record Policy(int windowHours, int feeBps, long feeCents, int maxFeeBps) {

        /** Free cancellation until then (the start itself with a window of 0). */
        public Instant freeUntil(Instant startsAt) {
            return startsAt.minus(Duration.ofHours(windowHours));
        }

        public boolean charges() {
            return windowHours > 0 && (feeBps > 0 || feeCents > 0);
        }
    }

    Policy of(String merchantId);
}
