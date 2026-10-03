package ca.northline.trust.application;

import ca.northline.shared.NavBadgeContributor;
import ca.northline.trust.api.QualityQuery;
import ca.northline.trust.api.RatingQuery;
import java.text.NumberFormat;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The public trust reads other modules compose (quality score, rating) and the {@code reviews} sidebar badge: the star
 * average in the caller's locale ("4.9" · "4,9"), none before the first review.
 *
 * <p>S-119: a rating is the star total and count of one grouped query — not the Reviews screen's summary (the
 * distribution and the praise tags, three aggregates) — and lists of businesses ask for all of theirs at once.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TrustScoreService implements QualityQuery, RatingQuery, NavBadgeContributor {

    private static final RatingSummary NONE = new RatingSummary(0, 0);

    private final QualityStore quality;
    private final ReviewStore reviews;

    @Override
    public Optional<QualityScore> latest(String merchantId) {
        return quality.latest(merchantId);
    }

    @Override
    public Map<String, QualityScore> latestOf(Collection<String> merchantIds) {
        return quality.latest(merchantIds);
    }

    @Override
    public RatingSummary summary(String merchantId) {
        return summaries(List.of(merchantId)).getOrDefault(merchantId, NONE);
    }

    @Override
    public Map<String, RatingSummary> summaries(Collection<String> merchantIds) {
        var totals = reviews.totals(merchantIds);
        return merchantIds.stream().distinct().collect(Collectors.toUnmodifiableMap(Function.identity(), id -> {
            var t = totals.get(id);
            return t == null ? NONE : new RatingSummary(TrustReviewService.average(t.stars(), t.count()), t.count());
        }));
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
