package ca.northline.trust.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.jspecify.annotations.Nullable;

/**
 * S-133 weekly anomaly scan: which of a business's week's counts stand out against its previous eight weeks. These
 * deterministic signals pick the businesses; the model only explains them to staff (and when it can't, {@link #describe}
 * does). Thresholds are deliberately conservative: a flag costs a moderator's time.
 */
public final class AnomalyRules {

    /** Reviews in a week before a burst can be one, and the multiple of the usual weekly count it must reach. */
    public static final int BURST_MIN_REVIEWS = 5;

    public static final double BURST_FACTOR = 3.0;

    /** Reviews needed this week and before, and the drop in average stars, for a rating drop. */
    public static final int DROP_MIN_REVIEWS = 3;

    public static final int DROP_MIN_PRIOR = 5;
    public static final double DROP_STARS = 1.0;

    /** Flags in a week that make a signal. */
    public static final int FLAGS_MIN = 3;

    /** The weeks the "usual" figures cover. */
    public static final int PRIOR_WEEKS = 8;

    private AnomalyRules() {}

    public enum Signal {
        REVIEW_BURST,
        RATING_DROP,
        OFF_PLATFORM,
        AI_FLAGS;

        public String code() {
            return name().toLowerCase(Locale.ROOT);
        }
    }

    /** One business's week. */
    public record Week(
            int reviews,
            @Nullable Double average,
            int priorReviews,
            @Nullable Double priorAverage,
            int offPlatformFlags,
            int aiFlags) {

        public double usualWeeklyReviews() {
            return priorReviews / (double) PRIOR_WEEKS;
        }
    }

    public static List<Signal> signals(Week w) {
        var out = new ArrayList<Signal>();
        if (w.reviews() >= BURST_MIN_REVIEWS && w.reviews() >= BURST_FACTOR * Math.max(1.0, w.usualWeeklyReviews())) {
            out.add(Signal.REVIEW_BURST);
        }
        var avg = w.average();
        var prior = w.priorAverage();
        if (avg != null
                && prior != null
                && w.reviews() >= DROP_MIN_REVIEWS
                && w.priorReviews() >= DROP_MIN_PRIOR
                && prior - avg >= DROP_STARS) {
            out.add(Signal.RATING_DROP);
        }
        if (w.offPlatformFlags() >= FLAGS_MIN) {
            out.add(Signal.OFF_PLATFORM);
        }
        if (w.aiFlags() >= FLAGS_MIN) {
            out.add(Signal.AI_FLAGS);
        }
        return List.copyOf(out);
    }

    /** The numbers behind the signals, in one line (sent to the model; also the fallback explanation). */
    public static String facts(Week w) {
        var s = new StringBuilder();
        s.append(String.format(
                Locale.ROOT,
                "reviews this week %d (previous %d weeks: %d, about %.1f a week)",
                w.reviews(),
                PRIOR_WEEKS,
                w.priorReviews(),
                w.usualWeeklyReviews()));
        if (w.average() != null) {
            s.append(String.format(Locale.ROOT, "; average rating this week %.1f", w.average()));
            if (w.priorAverage() != null) {
                s.append(String.format(Locale.ROOT, " (before %.1f)", w.priorAverage()));
            }
        }
        s.append("; off-platform payment flags ").append(w.offPlatformFlags());
        s.append("; screening flags ").append(w.aiFlags());
        return s.toString();
    }

    /** A plain explanation from the signals and numbers, for when the model is unavailable. */
    public static String describe(List<Signal> signals, Week w) {
        var names = signals.stream()
                .map(sig -> switch (sig) {
                    case REVIEW_BURST -> "review burst";
                    case RATING_DROP -> "rating drop";
                    case OFF_PLATFORM -> "repeated off-platform payment flags";
                    case AI_FLAGS -> "repeated screening flags";
                })
                .toList();
        return "Stands out this week: " + String.join(", ", names) + " — " + facts(w) + ".";
    }
}
