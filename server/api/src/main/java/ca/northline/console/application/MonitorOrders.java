package ca.northline.console.application;

import ca.northline.shared.CodedEnum;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * The console's orders monitor (S-81, design 03 {@code orders}): goods and food orders and service bookings side by
 * side, the ones that need attention first. Admin, dispatch and support open it.
 */
public interface MonitorOrders {

    Monitor monitor(Query query);

    /** The design's chips: Needs attention · Live · Escrow &gt; 48 h · Late · All. */
    enum View implements CodedEnum {
        ATTENTION,
        LIVE,
        ESCROW,
        LATE,
        ALL
    }

    /**
     * @param q a reference prefix (NL-48…, BK-77…)
     * @param province / {@code market}: region model ids, as on the overview
     */
    record Query(
            View view,
            @Nullable String q,
            @Nullable String province,
            @Nullable String market) {}

    /** What a row shows in the State column (design: Escrow · Issue · Late · Stuck · Escrow &gt; 48 h · Delivered). */
    enum Status implements CodedEnum {
        /** waiting for the seller or provider to accept */
        NEW,
        /** in progress: packing, on its way, booked, on site */
        LIVE,
        /** paid and held: the job or delivery is done, the customer hasn't confirmed yet */
        ESCROW,
        /** escrow held more than 48 h after completion */
        ESCROW_48H,
        /** a booking's provider hasn't started 15 min after the start, or a delivery past its window */
        LATE,
        /** the delivery's run has a stop more than 10 min past its ETA */
        STUCK,
        /** a reported problem: a line issue, a short or refunded line, a disputed booking */
        ISSUE,
        DELIVERED,
        DONE,
        CANCELLED
    }

    /**
     * @param kind {@code order} | {@code booking}
     * @param type {@code goods} | {@code food} | {@code service}
     * @param customer the customer's short name ("A. Osei")
     * @param sellers the businesses' names (an order may have several)
     * @param state the order's or booking's own state
     * @param attention one of the attention statuses (issue, late, stuck, escrow &gt; 48 h)
     * @param since when the status started counting (the window end, the start, the completion), when known
     */
    record Row(
            String id,
            @Nullable String ref,
            String kind,
            String type,
            @Nullable String customer,
            List<String> sellers,
            long amountCents,
            String state,
            Status status,
            boolean attention,
            Instant at,
            @Nullable Instant since) {

        public Row {
            sellers = List.copyOf(sellers);
        }
    }

    /**
     * @param week orders placed and bookings made in the last 7 days (cancelled ones left out)
     * @param counts rows per view among the open and recent ones
     * @param truncated more rows matched than were returned
     */
    record Monitor(Instant asOf, long week, Counts counts, List<Row> items, boolean truncated) {
        public Monitor {
            items = List.copyOf(items);
        }
    }

    record Counts(long attention, long live, long escrow, long late, long all) {}
}
