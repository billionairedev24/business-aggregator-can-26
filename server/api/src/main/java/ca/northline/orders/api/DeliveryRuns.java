package ca.northline.orders.api;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Pooled delivery runs (S-49/S-50/S-51, design 06 Shop: "Tonight's pooled run leaves 6:00 pm · order by 5:19"). Every
 * market gets the runs of {@code northline.orders.delivery.runs} each day (evening and next-morning by default); a run
 * is an {@code orders.delivery_windows} row, created the first time someone asks for it. Times are computed in the
 * market's time zone (region model).
 *
 * <ul>
 *   <li>{@code packBy} = the window's {@code cutoff_at}: shops have the order packed for the courier's pickup.
 *   <li>{@code orderBy} = {@code packBy} minus the packing lead ({@code northline.orders.delivery.order-lead}): the
 *       customer's cut-off, the one the Shop pages show.
 * </ul>
 */
public interface DeliveryRuns {

    /** The market for a city, in its display form (any case in), or empty where Northline doesn't deliver. */
    Optional<String> market(String city);

    /**
     * Runs a customer in {@code market} can still order for at {@code now} — today's and the next two days' — soonest
     * first. Empty for a city that isn't a market.
     */
    List<Run> upcoming(String market, Instant now);

    /** The market's local time zone, the one its runs' days and times are in (region model). */
    ZoneId zone(String market);

    /** One run by its window id (checkout, order tracking). */
    Optional<Run> run(String windowId);

    /** The direct-courier option ("Now · 45 min"), which isn't a pooled run. */
    Direct direct();

    /**
     * @param slot the configured run it comes from ({@code evening}, {@code morning}, …); {@code other} for a window
     *     created outside the schedule
     * @param label the run code couriers and sellers use ({@code R-611}), when it has one
     * @param households customers with an order on this run ("5 neighbours in")
     */
    record Run(
            String windowId,
            String market,
            String slot,
            @Nullable String label,
            Instant startsAt,
            Instant endsAt,
            Instant orderBy,
            Instant packBy,
            long feeCents,
            int households) {

        public boolean openAt(Instant now) {
            return now.isBefore(orderBy);
        }
    }

    record Direct(Duration eta, long feeCents) {}
}
