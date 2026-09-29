package ca.northline.trust.application;

import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.trust.api.ReviewReplied;
import ca.northline.trust.api.ReviewReported;
import ca.northline.trust.domain.ReportReason;
import ca.northline.trust.domain.Review;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Reviews screen: summary, pages of verified reviews, public reply and report (flag for trust &amp; safety). */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class TrustReviewService implements BrowseReviews, RespondToReview {

    static final int PRAISE_TAGS = 3;

    private final ReviewStore reviews;
    private final TrustFlagStore flags;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    @Override
    public Summary summary(String merchantId) {
        var distribution = reviews.distribution(merchantId);
        int count = distribution.stream().mapToInt(StarCount::count).sum();
        int stars = distribution.stream().mapToInt(s -> s.stars() * s.count()).sum();
        return new Summary(average(stars, count), count, distribution, reviews.praise(merchantId, PRAISE_TAGS));
    }

    @Override
    public Page reviews(String merchantId, int limit, int offset) {
        var rows = reviews.page(merchantId, limit + 1, offset);
        return rows.size() > limit ? new Page(rows.subList(0, limit), offset + limit) : new Page(rows, null);
    }

    @Override
    @Transactional
    public Review reply(String merchantId, String reviewId, String text, String actorId) {
        var review = find(merchantId, reviewId).replied(text, clock.instant());
        reviews.saveReply(review, actorId);
        events.publishEvent(new ReviewReplied(Ids.next(), clock.instant(), reviewId, merchantId, actorId));
        return review;
    }

    @Override
    @Transactional
    public Review report(
            String merchantId, String reviewId, ReportReason reason, @Nullable String note, String actorId) {
        var reported = find(merchantId, reviewId).reported(reason, note, clock.instant());
        reviews.saveReport(reported.review(), reported.note(), actorId);
        flags.raise(
                Ids.next(), "review", reviewId, "review_report", merchantId, actorId, Map.of("reason", reason.code()));
        events.publishEvent(
                new ReviewReported(Ids.next(), clock.instant(), reviewId, merchantId, actorId, reason.code()));
        return reported.review();
    }

    static double average(int stars, int count) {
        return count == 0
                ? 0
                : BigDecimal.valueOf(stars)
                        .divide(BigDecimal.valueOf(count), 1, RoundingMode.HALF_UP)
                        .doubleValue();
    }

    private Review find(String merchantId, String reviewId) {
        return reviews.find(merchantId, reviewId).orElseThrow(() -> new NotFound("review", reviewId));
    }
}
