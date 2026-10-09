package ca.northline.trust.api;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Customers' own reviews of a business (mobile gaps part 2, design 01 C11 / B9): posted once per transaction and
 * business, by the customer of a completed booking or a delivered order — the caller (the account module) checks that
 * and the 30-day window; trust checks the content, screens it ({@code review_screened} flag when something was masked)
 * and keeps the 24-hour edit window. The rating counts at once towards the business's rating.
 */
public interface ReviewPosting {

    /** A review can be written up to 30 days after the job was completed or the order delivered. */
    java.time.Duration WINDOW = java.time.Duration.ofDays(30);

    String NOT_REVIEWABLE = "You can review this once it's done.";
    String WINDOW_CLOSED = "Reviews can be written up to 30 days after the job or delivery.";
    String NOT_A_SELLER = "Choose a business from this order.";

    /**
     * @param refType {@code booking} | {@code order} (shop or food order)
     * @param authorName the display form stored with the review ("D. Kowalski")
     * @param jobLabel what was done or bought ("Brake inspection", "Food order FD-10012")
     * @param lang {@code en} | {@code fr}
     */
    record Draft(
            String authorId,
            String authorName,
            String refType,
            String refId,
            String merchantId,
            @Nullable String jobLabel,
            int rating,
            List<String> tags,
            @Nullable String text,
            String lang) {
        public Draft {
            tags = List.copyOf(tags);
        }
    }

    /**
     * @param screened the words were masked by the profanity / personal-information filter
     * @param editUntil until when the author may still change it (null once the business replied)
     * @param hidden trust &amp; safety hid it: it no longer counts nor shows
     */
    record Posted(
            String id,
            String merchantId,
            String refType,
            String refId,
            int rating,
            List<String> tags,
            @Nullable String text,
            boolean screened,
            Instant createdAt,
            @Nullable Instant editUntil,
            @Nullable Instant editedAt,
            @Nullable String reply,
            boolean hidden) {
        public Posted {
            tags = List.copyOf(tags);
        }
    }

    /** 409 {@code already_reviewed} for a second review of the same business and transaction. */
    Posted post(Draft draft);

    /** The author changes stars, tags or words inside the window; 409 {@code review_locked} after it. */
    Posted edit(String authorId, String reviewId, int rating, List<String> tags, @Nullable String text);

    /** The author's reviews of these transactions (bookings, orders). */
    List<Posted> mine(String authorId, Collection<String> refIds);
}
