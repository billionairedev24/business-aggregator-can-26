package ca.northline.payments.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.payments.api.ReleaseDelays.Delay;
import ca.northline.payments.application.ReleaseDelayStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link ReleaseDelayStore} over {@code payments.release_delays}. */
@Repository
@RequiredArgsConstructor
class ReleaseDelayJdbc implements ReleaseDelayStore {

    private static final String SELECT = """
            select d.booking_id, d.escrow_id, d.hours, d.delayed_until, d.created_at, d.lifted_at,
                   (e.state = 'released') as released
              from payments.release_delays d
              join payments.escrows e on e.id = d.escrow_id""";

    private final JdbcClient jdbc;

    @Override
    public boolean insert(String escrowId, String merchantId, String bookingId, int hours, Instant until, Instant at) {
        return jdbc.sql("""
                        insert into payments.release_delays
                          (escrow_id, merchant_id, booking_id, hours, delayed_until, created_at)
                        values (:escrow, :merchant, :booking, :hours, :until, :at) on conflict do nothing
                        """)
                        .param("escrow", escrowId)
                        .param("merchant", merchantId)
                        .param("booking", bookingId)
                        .param("hours", hours)
                        .param("until", ts(until))
                        .param("at", ts(at))
                        .update()
                > 0;
    }

    @Override
    public Optional<Instant> activeUntil(String escrowId) {
        return jdbc.sql("""
                        select delayed_until from payments.release_delays
                         where escrow_id = :escrow and lifted_at is null
                        """)
                .param("escrow", escrowId)
                .query((rs, _) -> requiredInstant(rs, "delayed_until"))
                .optional();
    }

    @Override
    public List<Delay> of(String merchantId, int limit) {
        return jdbc.sql(SELECT + " where d.merchant_id = :merchant order by d.created_at desc, d.escrow_id limit :n")
                .param("merchant", merchantId)
                .param("n", limit)
                .query(ReleaseDelayJdbc::delay)
                .list();
    }

    @Override
    public List<Delay> active(String bookingId) {
        return jdbc.sql(SELECT + " where d.booking_id = :booking and d.lifted_at is null for update of d")
                .param("booking", bookingId)
                .query(ReleaseDelayJdbc::delay)
                .list();
    }

    @Override
    public void lifted(String escrowId, String staffId, Instant at) {
        jdbc.sql("update payments.release_delays set lifted_at = :at, lifted_by = :by where escrow_id = :escrow")
                .param("at", ts(at))
                .param("by", staffId)
                .param("escrow", escrowId)
                .update();
    }

    private static Delay delay(ResultSet rs, int i) throws SQLException {
        return new Delay(
                rs.getString("booking_id"),
                rs.getString("escrow_id"),
                rs.getInt("hours"),
                requiredInstant(rs, "delayed_until"),
                requiredInstant(rs, "created_at"),
                instant(rs, "lifted_at"),
                rs.getBoolean("released"));
    }
}
