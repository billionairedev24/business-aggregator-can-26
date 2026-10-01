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
        @DefaultValue("5m") Duration positionTtl) {}
