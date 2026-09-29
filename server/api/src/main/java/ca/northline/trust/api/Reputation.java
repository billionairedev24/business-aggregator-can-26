package ca.northline.trust.api;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Map;
import java.util.Optional;

/**
 * Rating and quality score of a merchant (Dashboard KPIs and "Quality score"). Added by the operations workstream as a
 * read over {@code trust.reviews} and {@code trust.quality_scores}; the reviews workstream owns it.
 */
public interface Reputation {

    /** Average star rating of reviews targeting the merchant, with the number of reviews. */
    Optional<Rating> rating(String merchantId);

    /** Latest daily quality score. Components are percentages keyed by metric (see {@link QualityScore}). */
    Optional<QualityScore> latestQuality(String merchantId);

    record Rating(BigDecimal average, long count) {}

    /**
     * @param components {@code on_time}, {@code photos}, {@code response}, {@code rebook} (0–100) and
     *     {@code dispute_rate} (percent of jobs disputed, e.g. 0.3)
     */
    record QualityScore(LocalDate date, int score, Map<String, BigDecimal> components) {}
}
