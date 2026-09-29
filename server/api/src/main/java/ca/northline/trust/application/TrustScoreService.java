package ca.northline.trust.application;

import ca.northline.shared.NavBadgeContributor;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import java.text.NumberFormat;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public trust reads other modules compose (quality score, rating) and the {@code reviews} sidebar badge: the star
 * average in the caller's locale ("4.9" · "4,9"), none before the first review.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TrustScoreService implements QualityQuery, RatingQuery, NavBadgeContributor {

    private final QualityStore quality;
    private final BrowseReviews reviews;

    @Override
    public Optional<QualityScore> latest(String merchantId) {
        return quality.latest(merchantId);
    }

    @Override
    public RatingSummary summary(String merchantId) {
        var summary = reviews.summary(merchantId);
        return new RatingSummary(summary.average(), summary.count());
    }

    @Override
    public Map<String, String> badges(Context context) {
        var rating = summary(context.merchantId());
        if (rating.count() == 0) {
            return Map.of();
        }
        var format = NumberFormat.getNumberInstance(context.locale());
        format.setMinimumFractionDigits(1);
        format.setMaximumFractionDigits(1);
        return Map.of("reviews", format.format(rating.average()));
    }
}
