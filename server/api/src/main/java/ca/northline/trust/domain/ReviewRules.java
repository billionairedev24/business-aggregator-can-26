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

    // ── customers posting (mobile gaps part 2) ──────────────────────────────────────────────────────────────────

    /** The author may change it for 24 h after posting, while the business hasn't replied (DB trigger V360). */
    public static final java.time.Duration EDIT_WINDOW = java.time.Duration.ofHours(24);

    public static final int TEXT_MIN = 10;
    public static final int TEXT_MAX = 1000;
    public static final int TAGS_MAX = 5;

    /** The praise tags customers pick from (design 01 C11 / B9; the Studio's {@code tag_*} labels). */
    public static final java.util.Set<String> TAGS = java.util.Set.of(
            "on_time",
            "clear_explanation",
            "fair_price",
            "clean_work",
            "extra_mile",
            "friendly",
            "well_packed",
            "as_described",
            "hot_on_arrival",
            "tasty",
            "generous_portions");

    public static final String RATING_REQUIRED = "Choose from 1 to 5 stars.";
    public static final String TEXT_TOO_SHORT = "Write at least 10 characters, or leave the review empty.";
    public static final String TEXT_TOO_LONG = "Keep your review under 1,000 characters.";
    public static final String TAGS_INVALID = "Choose up to 5 of the tags offered.";
    public static final String ALREADY_REVIEWED = "You've already reviewed this.";
    public static final String EDIT_CLOSED = "This review can't be changed any more.";
}
