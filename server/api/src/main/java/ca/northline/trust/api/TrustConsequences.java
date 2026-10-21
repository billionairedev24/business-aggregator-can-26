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

    /** S-156: rule {@code provider_no_shows} (default 3 in 30 days → instant book off). */
    NoShowRule providerNoShowRule();

    /**
     * S-156: confirmed provider no-shows (flags {@code no_show} staff confirmed) per business within the rule's window,
     * for the businesses that have any.
     */
    java.util.Map<String, Integer> providerNoShows();

    /** S-156: rule {@code customer_no_shows} (default 2). */
    int customerNoShowLimit();

    /** S-156: confirmed customer no-shows ({@code customer_no_show}) per customer within {@code within}. */
    java.util.Map<String, Integer> customerNoShows(Duration within);

    record NoShowRule(int count, int days) {}

    record RatingFloor(double rating, int days, int recoverDays) {}

    record Below(String merchantId, double average, long reviews) {}
}
