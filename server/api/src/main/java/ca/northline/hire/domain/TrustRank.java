package ca.northline.hire.domain;

import java.util.Comparator;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * "Sorted by trust · tier, on-time rate, dispute rate and re-book rate" (design 06 provider list): Master before Trusted
 * before Registered, then the higher on-time rate, the lower dispute rate, the higher re-book rate, then the rating.
 * A business without a quality score yet sorts after those with one at the same tier.
 */
public final class TrustRank {
    private TrustRank() {}

    /**
     * @param onTime percent, null before the first nightly score
     * @param disputes percent (0.3 = 0.3 %)
     */
    public record Signals(
            String tier,
            @Nullable Double onTime,
            @Nullable Double disputes,
            @Nullable Double rebook,
            double rating,
            String name) {}

    private static final List<String> TIERS = List.of("master", "trusted", "registered");

    public static <T> Comparator<T> by(java.util.function.Function<T, Signals> signals) {
        Comparator<Signals> order = Comparator.<Signals>comparingInt(s -> tier(s.tier()))
                .thenComparingInt(s -> s.onTime() == null ? 1 : 0)
                .thenComparingDouble(s -> -value(s.onTime()))
                .thenComparingDouble(s -> s.disputes() == null ? Double.MAX_VALUE : s.disputes())
                .thenComparingDouble(s -> -value(s.rebook()))
                .thenComparingDouble(s -> -s.rating())
                .thenComparing(Signals::name);
        return Comparator.comparing(signals, order);
    }

    private static int tier(String tier) {
        var i = TIERS.indexOf(tier);
        return i < 0 ? TIERS.size() : i;
    }

    private static double value(@Nullable Double d) {
        return d == null ? 0 : d;
    }
}
