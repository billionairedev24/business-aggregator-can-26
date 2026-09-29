package ca.northline.trust.api;

/** Star average and count of verified reviews of a business ("4.9 · 312 verified reviews" on the dashboard). */
public interface RatingQuery {

    /** @param average rounded to one decimal; 0 when {@code count} is 0 */
    record RatingSummary(double average, int count) {}

    RatingSummary summary(String merchantId);
}
