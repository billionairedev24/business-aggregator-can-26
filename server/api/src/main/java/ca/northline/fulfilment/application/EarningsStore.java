package ca.northline.fulfilment.application;

import java.time.Instant;
import java.util.List;

/** Outbound port (S-151): what couriers earned per finished run ({@code fulfilment.courier_earnings}). */
public interface EarningsStore {

    /** Inserts unless the run was already paid; true when inserted. */
    boolean insert(Earning earning);

    /** The courier's runs paid in [{@code from}, {@code to}), latest first. */
    List<Earning> of(String courierUserId, Instant from, Instant to);

    /**
     * @param model the pay model it was paid under, as stored ({@code perRunCents}, …)
     */
    record Earning(
            String runId,
            String courierId,
            String courierUserId,
            String market,
            int pickups,
            int dropoffs,
            int failed,
            int collects,
            int returns,
            long runCents,
            long stopsCents,
            String model,
            Instant earnedAt) {

        public long totalCents() {
            return runCents + stopsCents;
        }
    }
}
