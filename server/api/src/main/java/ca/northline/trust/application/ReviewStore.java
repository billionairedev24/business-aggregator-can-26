package ca.northline.trust.application;

import ca.northline.trust.application.BrowseReviews.Praise;
import ca.northline.trust.application.BrowseReviews.StarCount;
import ca.northline.trust.domain.Review;
import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Outbound port: reviews of businesses ({@code trust.reviews}, {@code target_type = 'merchant'}). */
public interface ReviewStore {

    /** The sum of the stars and the number of reviews of a business. */
    record StarTotal(int stars, int count) {}

    /** Count per star (5 → 1, every star present). */
    List<StarCount> distribution(String merchantId);

    /** S-119: star totals of many businesses in one query; a business without reviews is absent. */
    Map<String, StarTotal> totals(Collection<String> merchantIds);

    /** Most frequent tags with their share of all reviews, up to {@code limit}. */
    List<Praise> praise(String merchantId, int limit);

    /** @param withHidden the business's own screen also lists reviews trust &amp; safety hid; public pages don't */
    List<Review> page(String merchantId, int limit, int offset, boolean withHidden);

    Optional<Review> find(String merchantId, String reviewId);

    void saveReply(Review review, String actorId);

    void saveReport(Review review, @Nullable String note, String actorId);

    // ── customers' own reviews (mobile gaps part 2) ─────────────────────────────────────────────────────────────

    /** A customer's review as its author sees and edits it. */
    record Authored(
            String id,
            String authorId,
            String merchantId,
            String refType,
            String refId,
            int rating,
            List<String> tags,
            @Nullable String text,
            String lang,
            boolean screened,
            Instant createdAt,
            @Nullable Instant editUntil,
            @Nullable Instant editedAt,
            @Nullable String reply,
            @Nullable Instant hiddenAt) {
        public Authored {
            tags = List.copyOf(tags);
        }
    }

    /** Inserts; false when the author already reviewed this business for this transaction. */
    boolean insert(Authored review, String authorName, @Nullable String jobLabel);

    Optional<Authored> authored(String reviewId);

    List<Authored> byAuthor(String authorId, Collection<String> refIds);

    /** The author's change inside the window; false when the window closed or the business replied meanwhile. */
    boolean edit(String reviewId, int rating, List<String> tags, @Nullable String text, boolean screened, Instant at);

    /** Trust &amp; safety hides ({@code reason} set) or shows again ({@code reason} null) a review. */
    boolean moderate(String reviewId, @Nullable String reason, String staffId, Instant at);
}
