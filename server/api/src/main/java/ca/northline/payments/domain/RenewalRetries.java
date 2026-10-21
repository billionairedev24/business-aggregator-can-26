package ca.northline.payments.domain;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * S-162: when a refused Plus renewal is tried again — offsets from the period's start ({@code PLUS_RETRY_DAYS}, by
 * default 1, 3 and 5 days); after the last one fails the membership lapses. Benefits continue meanwhile (the grace
 * window, which must cover the last retry).
 */
public record RenewalRetries(List<Duration> offsets) {

    public RenewalRetries {
        offsets = List.copyOf(offsets);
        var previous = Duration.ZERO;
        for (var offset : offsets) {
            if (offset.compareTo(previous) <= 0) {
                throw new IllegalArgumentException(
                        "Plus retries must come later each time, after the due date: " + offsets);
            }
            previous = offset;
        }
    }

    public static RenewalRetries days(List<Integer> days) {
        return new RenewalRetries(days.stream().map(Duration::ofDays).toList());
    }

    /** After {@code failedAttempts} refused tries of a period due at {@code due}: the next try, or empty = lapse. */
    public Optional<Instant> next(Instant due, int failedAttempts) {
        return failedAttempts >= 1 && failedAttempts <= offsets.size()
                ? Optional.of(due.plus(offsets.get(failedAttempts - 1)))
                : Optional.empty();
    }

    /** The last retry's offset (zero without retries): the grace window must reach past it. */
    public Duration last() {
        return offsets.isEmpty() ? Duration.ZERO : offsets.getLast();
    }
}
