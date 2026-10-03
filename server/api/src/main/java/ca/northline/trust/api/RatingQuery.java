package ca.northline.trust.api;

import java.util.Collection;
import java.util.Map;

/** Star average and count of verified reviews of a business ("4.9 · 312 verified reviews" on the dashboard). */
public interface RatingQuery {

    /** @param average rounded to one decimal; 0 when {@code count} is 0 */
    record RatingSummary(double average, int count) {}

    RatingSummary summary(String merchantId);

    /**
     * S-119: the summaries of many businesses in one query (lists of providers and kitchens), every id present — a
     * business without reviews has {@code (0, 0)}.
     */
    Map<String, RatingSummary> summaries(Collection<String> merchantIds);
}
