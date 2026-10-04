package ca.northline.golive.domain;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/** Whether shifts cover a period without a gap (the on-call gate: someone answers a page every minute). */
public final class Coverage {

    private Coverage() {}

    public record Span(Instant from, Instant to) {}

    /** The first instant in [from, to) nobody covers; empty when the shifts cover all of it. */
    public static Optional<Instant> firstGap(List<Span> shifts, Instant from, Instant to) {
        var covered = from;
        for (var s : shifts.stream().sorted(Comparator.comparing(Span::from)).toList()) {
            if (s.from().isAfter(covered)) {
                return Optional.of(covered);
            }
            if (s.to().isAfter(covered)) {
                covered = s.to();
            }
            if (!covered.isBefore(to)) {
                return Optional.empty();
            }
        }
        return covered.isBefore(to) ? Optional.of(covered) : Optional.empty();
    }
}
