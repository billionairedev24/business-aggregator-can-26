package ca.northline.payments.api;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/** Platform money for the console's finance screen (S-85): escrow, payouts in flight, revenue mix, take by tier, tax. */
public interface FinanceFigures {

    /** Money held in escrow now and how many escrows hold it. */
    Held escrowHeld();

    /** Payouts Stripe hasn't paid yet (pending, in transit): the amount, how many businesses, the earliest arrival. */
    InFlight payoutsInFlight();

    /**
     * Northline's revenue in [from, to) by source: fees taken at escrow ({@code take}), delivery fees
     * ({@code delivery}), and give-backs on refunds and disputes ({@code adjustments}, negative).
     */
    Revenue revenue(Instant from, Instant to);

    /** Money held in [from, to) per business (escrows by creation, any state): GMV through Northline's checkout. */
    Map<String, Long> heldByMerchant(Instant from, Instant to);

    /** The default take rate of each tier, in basis points ({@code registered}, {@code trusted}, {@code master}). */
    Map<String, Integer> defaultTakeRates();

    /**
     * Tax of a quarter ({@code 2026-Q3}) from the Stripe Tax read model (S-21): GST/HST on Northline's own fees, and
     * what Northline collected and remits as the marketplace facilitator.
     */
    Tax tax(String period);

    record Held(long cents, long items) {}

    record InFlight(long cents, long merchants, @Nullable Instant nextArrival) {}

    record Revenue(long takeCents, long deliveryCents, long adjustmentsCents) {
        public long total() {
            return takeCents + deliveryCents + adjustmentsCents;
        }
    }

    record Tax(String period, long platformFeeCents, long facilitatorCents) {}
}
