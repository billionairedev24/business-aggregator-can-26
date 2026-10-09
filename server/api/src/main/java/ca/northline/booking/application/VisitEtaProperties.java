package ca.northline.booking.application;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The service visit's live ETA ({@code northline.booking.eta.*}, mobile gaps part 2).
 *
 * @param minutesPerKm {@code BOOKING_ETA_MINUTES_PER_KM}: minutes per straight-line km (2.0 ≈ 30 km/h in a city)
 * @param shareInterval the shortest interval between two accepted shares of one visit
 * @param ttl how long a shared position is kept and counts as live (S-88: 5 minutes)
 */
@ConfigurationProperties("northline.booking.eta")
public record VisitEtaProperties(
        @DefaultValue("2.0") double minutesPerKm,
        @DefaultValue("PT5S") Duration shareInterval,
        @DefaultValue("PT5M") Duration ttl) {}
