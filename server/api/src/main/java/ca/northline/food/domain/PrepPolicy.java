package ca.northline.food.domain;

import ca.northline.shared.Conflict;

/**
 * Prep time the kitchen promises. Customers see {@code defaultPrepMin + bumpMin} ("Busy · +5 min", up to +30); an
 * accepted order also gets its slowest item's extra prep, and large orders ({@code orderCents ≥ largeOrderCents}) get
 * {@code largeOrderAddMin} on top.
 */
public record PrepPolicy(int defaultPrepMin, int bumpMin, long largeOrderCents, int largeOrderAddMin) {

    public static final int BUMP_STEP = 5;
    public static final int BUMP_MAX = 30;

    public int shownMin() {
        return defaultPrepMin + bumpMin;
    }

    public int promiseFor(int slowestItemAddMin, long orderCents) {
        return shownMin() + slowestItemAddMin + (orderCents >= largeOrderCents ? largeOrderAddMin : 0);
    }

    /** "Busy · +5 min". 409 {@code prep_bump_max} past +30. */
    public PrepPolicy bumped() {
        if (bumpMin + BUMP_STEP > BUMP_MAX) {
            throw new Conflict("prep_bump_max", "Prep time is already at +30 min.");
        }
        return new PrepPolicy(defaultPrepMin, bumpMin + BUMP_STEP, largeOrderCents, largeOrderAddMin);
    }

    public PrepPolicy reset() {
        return new PrepPolicy(defaultPrepMin, 0, largeOrderCents, largeOrderAddMin);
    }
}
