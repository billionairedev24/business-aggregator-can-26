package ca.northline.account.domain;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * "Something's wrong" (S-60, design 06 / consumer app `refund`): what can be reported, the reasons, and when. Refunds
 * are never instant — a report opens a case — and only money still in its escrow window can be asked back: goods until
 * 7 days after delivery, services until 48 h after completion (both: until the escrow releases). Food is released at
 * handoff, so a food problem may be reported for {@link #FOOD_WINDOW} after it (the refund then comes back from the
 * kitchen's balance — S-11's transfer reversal).
 */
public final class ProblemRules {
    private ProblemRules() {}

    public static final Duration FOOD_WINDOW = Duration.ofHours(24);

    /** Design 06: Missing, Damaged, Wrong item, Poor quality, Late. */
    public static final List<String> GOODS_REASONS = List.of("missing", "damaged", "wrong_item", "poor_quality", "late");

    /** Services: the job wasn't done, done badly, late, nobody came, charged wrongly. */
    public static final List<String> SERVICE_REASONS = List.of("not_done", "poor_quality", "late", "no_show", "billing");

    /** A reason → S-132's case category (used when the report wasn't triaged). */
    public static final Map<String, String> CATEGORIES = Map.of(
            "missing", "missing_item",
            "damaged", "damaged",
            "wrong_item", "wrong_item",
            "late", "late",
            "not_done", "service_not_done",
            "no_show", "no_show",
            "billing", "billing");

    /** S-132's categories. */
    public static final List<String> TRIAGE_CATEGORIES = List.of(
            "missing_item", "wrong_item", "damaged", "not_as_described", "late", "not_delivered", "service_not_done",
            "service_quality", "no_show", "billing", "safety", "account", "other");

    public static final String ITEMS_REQUIRED = "Pick at least one item.";
    public static final String REASON_REQUIRED = "Pick what went wrong.";
    public static final String NOTE_TOO_LONG = "Keep it under 1,000 characters.";
    public static final int NOTE_MAX = 1000;
    public static final String NOT_FULFILLED = "You can report a problem once it's delivered or done.";
    public static final String WINDOW_CLOSED = "The time to report a problem with this has passed. Contact Northline from Help & cases.";
    public static final String ALREADY_REPORTED = "You've already reported this. Follow it in Help & cases.";
    public static final String NOT_PAID = "Nothing was paid for this, so there's nothing to refund.";

    /** English words for the business's refund case and the staff case ("Damaged · Kale bunch"). */
    public static final Map<String, String> REASON_WORDS = Map.of(
            "missing", "Missing",
            "damaged", "Damaged",
            "wrong_item", "Wrong item",
            "poor_quality", "Poor quality",
            "late", "Late",
            "not_done", "Not done",
            "no_show", "No-show",
            "billing", "Charged wrongly");

    public static List<String> reasons(String kind) {
        return "booking".equals(kind) ? SERVICE_REASONS : GOODS_REASONS;
    }

    public static String category(String kind, String reason) {
        if ("poor_quality".equals(reason)) {
            return "booking".equals(kind) ? "service_quality" : "not_as_described";
        }
        return CATEGORIES.getOrDefault(reason, "other");
    }
}
