package ca.northline.fulfilment.application;

import java.nio.file.Path;
import java.time.Duration;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * {@code northline.fulfilment} (S-86). Defaults are ours (the spec gives none); see DECISIONS "S-86".
 *
 * @param maxDropsPerRun drop-offs one courier takes on a pooled run; a fuller window is split into parts
 * @param assignLead how long before a pooled run starts a courier is assigned (direct runs: at once)
 * @param minutesPerKm straight-line minutes per km for ETAs
 * @param addressRetention how long a drop-off address is kept after the delivery ends
 * @param proofDir where proofs go under {@code local}/{@code test} (default {@code $TMPDIR/northline-proofs})
 * @param pingInterval S-88: the fastest a courier's app may send its position (customers see updates ≤ 5 s apart)
 * @param positionTtl S-88: how long the latest position is kept in Valkey (no history)
 * @param courierPay S-151: the platform's courier pay model, under every province's and market's own
 *     ({@code region.regions.courier_pay}); ours, see DECISIONS "S-151"
 * @param returnJoinWithin S-151: a return pickup rides on the market's planned pooled run starting this soon
 * @param returnDirectAfter S-151: a return pickup no pooled run took gets a run of its own after this long
 */
@ConfigurationProperties("northline.fulfilment")
public record FulfilmentProperties(
        @DefaultValue("12") int maxDropsPerRun,
        @DefaultValue("60m") Duration assignLead,
        @DefaultValue("3.0") double minutesPerKm,
        @DefaultValue("4m") Duration minLeg,
        @DefaultValue("8m") Duration unknownLeg,
        @DefaultValue("5m") Duration pickupDwell,
        @DefaultValue("3m") Duration dropoffDwell,
        @DefaultValue("30d") Duration addressRetention,
        @Nullable Path proofDir,
        @DefaultValue("2s") Duration pingInterval,
        @DefaultValue("5m") Duration positionTtl,
        @DefaultValue CourierPay courierPay,
        @DefaultValue("24h") Duration returnJoinWithin,
        @DefaultValue("24h") Duration returnDirectAfter) {

    /**
     * What a courier earns for a finished run: a base per run, plus per shop picked up from, per door delivered, per
     * failed attempt at a door (they still went), per return collected and per shop a return went back to.
     */
    public record CourierPay(
            @DefaultValue("400") long perRunCents,
            @DefaultValue("100") long perPickupCents,
            @DefaultValue("300") long perDropoffCents,
            @DefaultValue("300") long perFailedCents,
            @DefaultValue("300") long perCollectCents,
            @DefaultValue("100") long perReturnCents) {

        /** These defaults overlaid by {@code values} (the region model's keys). */
        public CourierPay with(java.util.Map<String, Long> values) {
            return new CourierPay(
                    values.getOrDefault("perRunCents", perRunCents),
                    values.getOrDefault("perPickupCents", perPickupCents),
                    values.getOrDefault("perDropoffCents", perDropoffCents),
                    values.getOrDefault("perFailedCents", perFailedCents),
                    values.getOrDefault("perCollectCents", perCollectCents),
                    values.getOrDefault("perReturnCents", perReturnCents));
        }
    }
}
