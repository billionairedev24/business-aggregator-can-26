package ca.northline.trust.api;

import java.time.Duration;
import java.util.List;

/**
 * What the trust rules (S-93) say should happen to businesses now, for the console's nightly enforcement (S-82): who is
 * below the rating floor, and who mentioned off-platform payment again after a warning. Trust decides; merchants
 * applies ({@code merchants.api.SellerSanctions}).
 */
public interface TrustConsequences {

    /** The rating floor rule now (its value, else the default 4.2 over 90 days, 30 days to recover). */
    RatingFloor ratingFloor();

    /** Businesses with enough reviews in the floor's window (5) whose average is below the floor. */
    List<Below> belowRatingFloor();

    /** Of {@code merchantIds}, those whose average is back at or above the floor, or without enough reviews any more. */
    List<String> recovered(List<String> merchantIds);

    /**
     * Businesses with an open off-platform payment flag raised after one of their off-platform flags was actioned
     * "warn" in the last {@code within} — the design's "warning, then suspension".
     */
    List<String> offPlatformAfterWarning(Duration within);

    record RatingFloor(double rating, int days, int recoverDays) {}

    record Below(String merchantId, double average, long reviews) {}
}
