package ca.northline.trust.application;

import org.jspecify.annotations.Nullable;

/**
 * Trust &amp; safety hide a review (a business's report or a screening flag upheld) or show it again. A hidden review
 * leaves the public pages and the business's rating; the business still sees it, marked, in its Reviews screen.
 */
public interface ReviewModeration {

    String NOT_A_REVIEW = "Only a review can be hidden.";
    String REASON_REQUIRED = "Say why the review is hidden.";

    /** @return false when it was already in that state */
    boolean hide(String reviewId, String reason, String staffId, String role, @Nullable String note);

    boolean show(String reviewId, String staffId, String role, @Nullable String note);
}
