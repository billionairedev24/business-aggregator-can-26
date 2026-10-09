package ca.northline.promotions.domain;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.stream.IntStream;

/**
 * Spreads an amount over lines in proportion to their weights, never more than each line's cap: whole cents, the
 * cents left by rounding down go to the largest lines first, and what a capped line can't take moves to the others.
 * The result never exceeds Σ caps.
 */
public final class Allocation {
    private Allocation() {}

    public static List<Long> spread(long total, List<Long> weights, List<Long> caps) {
        var n = weights.size();
        var out = new ArrayList<Long>();
        for (int i = 0; i < n; i++) {
            out.add(0L);
        }
        var left = Math.min(total, caps.stream().mapToLong(Long::longValue).sum());
        while (left > 0) {
            var open = IntStream.range(0, n)
                    .filter(i -> out.get(i) < caps.get(i) && weights.get(i) > 0)
                    .boxed()
                    .toList();
            if (open.isEmpty()) {
                break;
            }
            var weight = open.stream().mapToLong(weights::get).sum();
            var given = 0L;
            for (var i : open) {
                var share = Math.min(caps.get(i) - out.get(i), left * weights.get(i) / weight);
                out.set(i, out.get(i) + share);
                given += share;
            }
            left -= given;
            if (given == 0) {
                // rounding: one cent at a time to the largest lines with room
                var byWeight = open.stream()
                        .sorted(Comparator.comparing((Integer i) -> weights.get(i))
                                .reversed()
                                .thenComparing(i -> i))
                        .toList();
                for (var i : byWeight) {
                    if (left == 0) {
                        break;
                    }
                    if (out.get(i) < caps.get(i)) {
                        out.set(i, out.get(i) + 1);
                        left--;
                    }
                }
            }
        }
        return List.copyOf(out);
    }
}
