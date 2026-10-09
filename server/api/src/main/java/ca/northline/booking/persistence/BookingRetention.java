package ca.northline.booking.persistence;

import ca.northline.shared.privacy.PersonalDataContributor.Hold;
import ca.northline.shared.privacy.RetentionContributor;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

/**
 * S-107, booking.
 *
 * <ul>
 *   <li>{@code booking.checkin_locations} — "GPS check-in records: 90 days, or until a related dispute is closed": the
 *       location a team member's transition was recorded at ({@code booking_events.geom}) is cleared; the transition
 *       (who, when, what) stays the business's record.
 *   <li>{@code booking.sales_records} — "Transaction and tax records: 7 years": a booking that ended seven years ago
 *       keeps its service, dates, price and tax and loses the customer, the address and the job details; its sealed
 *       access notes go. Quote requests lose the customer and their description; accepted-quote snapshots go.
 * </ul>
 *
 * A booking still to happen is a legal hold for every module ({@link Hold#UPCOMING_BOOKING}).
 */
@Component
@RequiredArgsConstructor
class BookingRetention implements RetentionContributor {

    static final String CHECKINS = "booking.checkin_locations";
    static final String SALES_RECORDS = "booking.sales_records";

    private static final String OPEN = "('requested', 'confirmed', 'en_route', 'on_site', 'disputed')";

    private static final String LOCATED = """
            from booking.booking_events e
             where e.geom is not null and e.at < :cutoff
               and not ('booking:' || coalesce(e.booking_id, '') = any(:held)) and not (coalesce(e.actor_id, '') = any(:subjects))
            """;
    private static final String BOOKINGS = """
            from booking.bookings b
             where coalesce(b.ends_at, b.starts_at, b.created_at) < :cutoff and b.state not in %s
               and (b.customer_id is not null or b.address_id is not null or b.address_line is not null
                    or b.site_lat is not null
                    or b.area is not null or coalesce(b.details, '{}'::jsonb) <> '{}'::jsonb
                    or exists (select 1 from booking.access_notes n where n.booking_id = b.id))
               and not ('booking:' || b.id = any(:held)) and not (coalesce(b.customer_id, '') = any(:subjects))
            """.formatted(OPEN);
    private static final String QUOTE_REQUESTS = """
            from booking.quote_requests q
             where q.created_at < :cutoff
               and (q.customer_id is not null or coalesce(q.details, '{}'::jsonb) <> '{}'::jsonb
                    or cardinality(coalesce(q.media, '{}')) > 0)
               and not (coalesce(q.customer_id, '') = any(:subjects))
            """;
    private static final String ACCEPTANCES = """
            from booking.quote_acceptances a
             where coalesce(a.accepted_at, a.started_at) < :cutoff
               and not ('booking:' || a.booking_id = any(:held)) and not (a.customer_id = any(:subjects))
            """;

    private final JdbcClient jdbc;

    @Override
    public String module() {
        return "booking";
    }

    @Override
    public Set<String> categories() {
        return Set.of(CHECKINS, SALES_RECORDS);
    }

    @Override
    public List<HeldRef> holds(Instant now) {
        return jdbc
                .sql("select id from booking.bookings where state in " + BookingPersonalData.UPCOMING)
                .query((rs, _) -> rs.getString(1))
                .list()
                .stream()
                .map(id -> HeldRef.open("booking", id, Hold.UPCOMING_BOOKING))
                .toList();
    }

    @Override
    public long expired(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case CHECKINS -> count(LOCATED, p);
            case SALES_RECORDS -> count(BOOKINGS, p) + count(QUOTE_REQUESTS, p) + count(ACCEPTANCES, p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    private long count(String from, Map<String, Object> p) {
        return jdbc.sql("select count(*) " + from).params(p).query(Long.class).single();
    }

    @Override
    public long purge(Run run) {
        var p = params(run);
        return switch (run.category()) {
            case CHECKINS -> jdbc.sql("""
                            update booking.booking_events set geom = null
                             where id in (select e.id %s order by e.at limit :batch)
                            """.formatted(LOCATED)).params(p).update();
            case SALES_RECORDS -> salesRecords(p);
            default -> throw new IllegalArgumentException(run.category());
        };
    }

    private long salesRecords(Map<String, Object> p) {
        var ids = jdbc.sql("select b.id " + BOOKINGS + " limit :batch")
                .params(p)
                .query((rs, _) -> rs.getString(1))
                .list();
        long done = 0;
        if (!ids.isEmpty()) {
            var array = ids.toArray(String[]::new);
            jdbc.sql("delete from booking.access_notes where booking_id = any(:ids)")
                    .param("ids", array)
                    .update();
            done += jdbc.sql("""
                            update booking.bookings
                               set customer_id = null, address_id = null, address_line = null, area = null,
                                   site_lat = null, site_lng = null, details = '{}'::jsonb, updated_at = now(),
                                   version = version + 1
                             where id = any(:ids)
                            """).param("ids", array).update();
        }
        done += jdbc.sql("""
                        update booking.quote_requests set customer_id = null, details = '{}'::jsonb, media = '{}'
                         where id in (select q.id %s limit :batch)
                        """.formatted(QUOTE_REQUESTS)).params(p).update();
        done += jdbc.sql("delete from booking.quote_acceptances where quote_id in (select a.quote_id %s limit :batch)"
                        .formatted(ACCEPTANCES))
                .params(p)
                .update();
        return done;
    }

    private static Map<String, Object> params(Run run) {
        return Map.of("cutoff", run.before(), "held", run.heldKeys(), "subjects", run.subjects(), "batch", run.batch());
    }
}
