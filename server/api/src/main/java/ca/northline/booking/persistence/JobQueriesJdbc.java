package ca.northline.booking.persistence;

import ca.northline.booking.application.JobQueries;
import ca.northline.booking.domain.Approval;
import ca.northline.booking.domain.BookingState;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Read models of the Appointments screen. {@code details} (jsonb, written by the consumer booking flow) carries
 * {@code vehicle}, {@code access} (parking / door code) and the customer's {@code note}.
 */
@Repository
@RequiredArgsConstructor
class JobQueriesJdbc implements JobQueries {

    private static final String JOB_COLUMNS = """
            b.id, b.ref, b.title, b.starts_at, coalesce(b.ends_at, b.starts_at) as ends_at, b.state, b.member_user_id,
            b.customer_id, b.area, b.price_cents
            """;

    private final JdbcClient jdbc;

    @Override
    public List<JobRow> jobs(String merchantId, Instant from, Instant to, @Nullable String memberUserId) {
        return jdbc.sql("select " + JOB_COLUMNS + """
                          from booking.bookings b
                         where b.merchant_id = :merchantId and b.starts_at >= :from and b.starts_at < :to
                           and b.state <> 'cancelled'
                           and (cast(:member as text) is null or b.member_user_id = :member)
                         order by b.starts_at, b.id
                        """)
                .param("merchantId", merchantId)
                .param("from", JdbcTimes.ts(from))
                .param("to", JdbcTimes.ts(to))
                .param("member", memberUserId, java.sql.Types.VARCHAR)
                .query((rs, _) -> job(rs))
                .list();
    }

    @Override
    public Optional<JobCard> card(String merchantId, String bookingId) {
        return jdbc.sql("select " + JOB_COLUMNS + """
                               , b.address_line, b.details ->> 'access' as access, b.details ->> 'vehicle' as vehicle,
                               b.details ->> 'note' as note, b.escrow_id is not null as paid
                          from booking.bookings b where b.id = :id and b.merchant_id = :merchantId
                        """)
                .param("id", bookingId)
                .param("merchantId", merchantId)
                .query((rs, _) -> new JobCard(
                        job(rs),
                        rs.getString("address_line"),
                        rs.getString("access"),
                        rs.getString("vehicle"),
                        rs.getString("note"),
                        rs.getBoolean("paid")))
                .optional();
    }

    @Override
    public List<LogRow> log(String bookingId) {
        return jdbc.sql("""
                        select type, at, actor_id, note, media_id from booking.booking_events
                         where booking_id = :id order by at, id
                        """)
                .param("id", bookingId)
                .query((rs, _) -> new LogRow(
                        rs.getString("type"),
                        JdbcTimes.requiredInstant(rs, "at"),
                        rs.getString("actor_id"),
                        rs.getString("note"),
                        rs.getString("media_id")))
                .list();
    }

    @Override
    public List<Approval> approvals(String bookingId) {
        return jdbc.sql("""
                        select id, booking_id, description, amount_cents, state, requested_at, requested_by, decided_at
                          from booking.approvals where booking_id = :id order by requested_at, id
                        """)
                .param("id", bookingId)
                .query((rs, _) -> {
                    var decided = JdbcTimes.instant(rs, "decided_at");
                    return new Approval(
                            rs.getString("id"),
                            rs.getString("booking_id"),
                            rs.getString("description"),
                            rs.getLong("amount_cents"),
                            CodedEnum.fromCode(Approval.State.class, rs.getString("state")),
                            JdbcTimes.requiredInstant(rs, "requested_at"),
                            java.util.Objects.requireNonNullElse(rs.getString("requested_by"), ""),
                            decided);
                })
                .list();
    }

    @Override
    public long pastJobs(String merchantId, String customerId, Instant before) {
        return jdbc.sql("""
                        select count(*) from booking.bookings
                         where merchant_id = :merchantId and customer_id = :customerId and starts_at < :before
                           and state in ('completed', 'signed_off')
                        """)
                .param("merchantId", merchantId)
                .param("customerId", customerId)
                .param("before", JdbcTimes.ts(before))
                .query(Long.class)
                .single();
    }

    private static JobRow job(ResultSet rs) throws SQLException {
        long priceValue = rs.getLong("price_cents");
        Long price = rs.wasNull() ? null : priceValue;
        return new JobRow(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("title"),
                JdbcTimes.requiredInstant(rs, "starts_at"),
                JdbcTimes.requiredInstant(rs, "ends_at"),
                CodedEnum.fromCode(BookingState.class, rs.getString("state")),
                rs.getString("member_user_id"),
                rs.getString("customer_id"),
                rs.getString("area"),
                price);
    }
}
