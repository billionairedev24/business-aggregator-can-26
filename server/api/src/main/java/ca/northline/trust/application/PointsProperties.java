package ca.northline.trust.application;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Points at checkout ({@code northline.points.*}, mobile gaps part 2).
 *
 * @param pointsPerDollar {@code NORTHLINE_POINTS_PER_DOLLAR}: points for $1 off (100: 1 point = 1 cent)
 * @param maxOrderPercent {@code NORTHLINE_POINTS_MAX_ORDER_PERCENT}: at most this share of an order's amount
 * @param minRedeem {@code NORTHLINE_POINTS_MIN_REDEEM}: the smallest number of points spent at once
 */
@ConfigurationProperties("northline.points")
public record PointsProperties(
        @DefaultValue("100") int pointsPerDollar,
        @DefaultValue("50") int maxOrderPercent,
        @DefaultValue("100") long minRedeem) {

    public PointsProperties {
        if (pointsPerDollar < 1 || maxOrderPercent < 0 || maxOrderPercent > 100 || minRedeem < 1) {
            throw new IllegalArgumentException("northline.points: pointsPerDollar ≥ 1, maxOrderPercent 0–100, minRedeem ≥ 1");
        }
    }
}
