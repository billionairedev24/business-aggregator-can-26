package ca.northline.trust.application;

import ca.northline.trust.application.BrowseReviews.Praise;
import ca.northline.trust.application.BrowseReviews.StarCount;
import ca.northline.trust.domain.Review;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: reviews of businesses ({@code trust.reviews}, {@code target_type = 'merchant'}). */
public interface ReviewStore {

    /** Count per star (5 → 1, every star present). */
    List<StarCount> distribution(String merchantId);

    /** Most frequent tags with their share of all reviews, up to {@code limit}. */
    List<Praise> praise(String merchantId, int limit);

    List<Review> page(String merchantId, int limit, int offset);

    Optional<Review> find(String merchantId, String reviewId);

    void saveReply(Review review, String actorId);

    void saveReport(Review review, @Nullable String note, String actorId);
}
