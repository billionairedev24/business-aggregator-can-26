package ca.northline.trust.application;

import ca.northline.trust.domain.ReportReason;
import ca.northline.trust.domain.Review;
import org.jspecify.annotations.Nullable;

/** Public reply (once) and report (once) — the only things a business can do with a verified review. */
public interface RespondToReview {

    Review reply(String merchantId, String reviewId, String text, String actorId);

    Review report(String merchantId, String reviewId, ReportReason reason, @Nullable String note, String actorId);
}
