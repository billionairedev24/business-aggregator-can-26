package ca.northline.trust.domain;

/**
 * Limits and exact messages for replying to and reporting reviews (not in validation-rules.md; mirrored in the
 * client's {@code features/reviews/validation.ts}).
 */
public final class ReviewRules {

    private ReviewRules() {}

    public static final int REPLY_MAX = 1000;
    public static final int NOTE_MAX = 500;

    public static final String REPLY_REQUIRED = "Write a reply before sending.";
    public static final String REPLY_TOO_LONG = "Keep replies under 1,000 characters.";
    public static final String REASON_REQUIRED = "Choose a reason.";
    public static final String NOTE_REQUIRED = "Tell us what's wrong with this review.";
    public static final String NOTE_TOO_LONG = "Keep the note under 500 characters.";

    public static final String REASON_CODES = "fake|offensive|personal_info|wrong_business|other";
}
