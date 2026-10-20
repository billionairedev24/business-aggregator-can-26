package ca.northline.trust.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * S-152: a person's points from their ledger rows, first-to-expire spent first. Credits ({@code delta > 0}) may expire;
 * every debit (redemptions, reversals) uses the credits that expire soonest, then those that never expire; a debit
 * larger than what is left is carried as a debt the next credits pay first (a refund can take back points already
 * spent). A credit expires with whatever of it is left at its expiry. Rows of type {@code expiry} are the job's record
 * of what this computed and are ignored here, so the balance never counts an expiry twice.
 *
 * <p>Events are taken in time order — the rows by when they were written, each credit's expiry at its expiry time —
 * so what expired never changes after the fact.
 */
public final class PointsBalance {

    public static final String EXPIRY = "expiry";

    /** One ledger row. */
    public record Row(
            String id,
            long delta,
            String refType,
            Instant at,
            @Nullable Instant expiresAt) {}

    /**
     * @param available points that can be spent now (never negative)
     * @param debt points taken back beyond what was left (paid by the next credits)
     * @param expired what each credit had left when it expired, by credit id (only credits expired by now)
     * @param nextExpiry when the next not-yet-expired points expire, and how many; null when none do
     */
    public record Result(
            long available,
            long debt,
            Map<String, Long> expired,
            @Nullable Expiring nextExpiry) {
        public Result {
            expired = Map.copyOf(expired);
        }
    }

    public record Expiring(Instant at, long points) {}

    private static final class Open {
        final String id;
        final @Nullable Instant expiresAt;
        final Instant at;
        long left;

        Open(String id, long left, @Nullable Instant expiresAt, Instant at) {
            this.id = id;
            this.left = left;
            this.expiresAt = expiresAt;
            this.at = at;
        }
    }

    private static final Comparator<Open> SPEND_ORDER = Comparator.<Open, Instant>comparing(
                    o -> o.expiresAt == null ? Instant.MAX : o.expiresAt)
            .thenComparing(o -> o.at)
            .thenComparing(o -> o.id);

    private PointsBalance() {}

    public static Result of(List<Row> rows, Instant now) {
        record Event(Instant at, int order, Row row, boolean expiry) {}
        var events = new ArrayList<Event>();
        for (var r : rows) {
            if (EXPIRY.equals(r.refType()) || r.delta() == 0) {
                continue;
            }
            events.add(new Event(r.at(), 1, r, false));
            var expires = r.expiresAt();
            if (r.delta() > 0 && expires != null && !expires.isAfter(now)) {
                events.add(new Event(expires, 0, r, true)); // at the same instant, expiry first
            }
        }
        events.sort(Comparator.comparing(Event::at)
                .thenComparingInt(Event::order)
                .thenComparing(e -> e.row().id()));
        var open = new ArrayList<Open>();
        var expired = new LinkedHashMap<String, Long>();
        long debt = 0;
        for (var e : events) {
            var r = e.row();
            if (e.expiry()) {
                var credit = open.stream().filter(o -> o.id.equals(r.id())).findFirst();
                credit.ifPresent(o -> {
                    expired.put(o.id, o.left);
                    open.remove(o);
                });
                if (credit.isEmpty()) {
                    expired.put(r.id(), 0L);
                }
            } else if (r.delta() > 0) {
                var paid = Math.min(debt, r.delta());
                debt -= paid;
                if (r.delta() - paid > 0) {
                    open.add(new Open(r.id(), r.delta() - paid, r.expiresAt(), r.at()));
                }
            } else {
                var need = -r.delta();
                open.sort(SPEND_ORDER);
                for (var o : open) {
                    if (need == 0) {
                        break;
                    }
                    var take = Math.min(o.left, need);
                    o.left -= take;
                    need -= take;
                }
                open.removeIf(o -> o.left == 0); // a used-up credit's expiry finds nothing left
                debt += need;
            }
        }
        // credits pay a debt first, so while there is a debt nothing is left open
        var available = debt > 0 ? 0 : open.stream().mapToLong(o -> o.left).sum();
        Expiring next = null;
        var soonest = open.stream()
                .filter(o -> o.left > 0 && o.expiresAt != null)
                .map(o -> o.expiresAt)
                .filter(Objects::nonNull)
                .min(Comparator.naturalOrder());
        if (soonest.isPresent()) {
            var at = soonest.get();
            var points = open.stream()
                    .filter(o -> at.equals(o.expiresAt))
                    .mapToLong(o -> o.left)
                    .sum();
            next = new Expiring(at, points);
        }
        return new Result(available, debt, expired, next);
    }
}
