package ca.northline.trust.application;

import ca.northline.trust.domain.Review;
import java.util.List;
import org.jspecify.annotations.Nullable;

/** Reviews screen: the rating summary (distribution, praise tags) and the verified reviews, newest first. */
public interface BrowseReviews {

    record StarCount(int stars, int count, int percent) {}

    record Praise(String tag, int percent) {}

    record Summary(double average, int count, List<StarCount> distribution, List<Praise> praise) {}

    record Page(List<Review> items, @Nullable Integer nextOffset) {}

    Summary summary(String merchantId);

    Page reviews(String merchantId, int limit, int offset);
}
