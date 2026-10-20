package ca.northline.booking.persistence;

import static ca.northline.shared.JdbcTimes.instant;
import static ca.northline.shared.JdbcTimes.requiredInstant;
import static ca.northline.shared.JdbcTimes.ts;

import ca.northline.booking.api.BookingMoney.Balance;
import ca.northline.booking.api.BookingMoney.Cancellation;
import ca.northline.booking.api.BookingMoney.DueBalance;
import ca.northline.booking.api.BookingMoney.Facts;
import ca.northline.booking.application.BookingMoneyStore;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link BookingMoneyStore} over the S-153 columns of {@code booking.bookings} (V407). */
@Repository
@RequiredArgsConstructor
class BookingMoneyJdbc implements BookingMoneyStore {

    private static final String FACTS = """
            select b.id, coalesce(b.ref, '') as ref, b.merchant_id, b.customer_id, b.state, coalesce(b.title, 'Job') as title,
                   b.starts_at, coalesce(b.price_cents, 0) as price, coalesce(b.deposit_cents, 0) as deposit,
                   coalesce(b.tax_cents, 0) as tax, b.escrow_id is not null as paid, b.quote_id,
                   b.cancel_window_hours, b.cancel_fee_bps, b.free_cancel_until,
                   b.balance_state, b.balance_cents, b.balance_tax_cents, b.balance_due_at, b.balance_next_at,
                   b.balance_attempts, b.balance_failure,
                   b.cancelled_at, b.cancelled_by, coalesce(b.cancel_fee_cents, 0) as fee,
                   coalesce(b.cancel_fee_tax_cents, 0) as fee_tax
              from booking.bookings b
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<Facts> facts(String bookingId) {
        return jdbc.sql(FACTS + " where b.id = :id and b.customer_id is not null")
                .param("id", bookingId)
                .query((rs, _) -> facts(rs))
                .optional();
    }

    @Override
    public List<Facts> ofCustomer(String customerId, int limit) {
        return jdbc.sql(FACTS + " where b.customer_id = :c order by b.starts_at desc, b.id desc limit :limit")
                .param("c", customerId)
                .param("limit", limit)
                .query((rs, _) -> facts(rs))
                .list();
    }

    @Override
    public void scheduleBalance(String bookingId, long cents, long taxCents, Instant dueAt) {
        jdbc.sql("""
                        update booking.bookings
                           set balance_state = 'pending', balance_cents = :cents, balance_tax_cents = :tax,
                               balance_due_at = :due, balance_next_at = :due, balance_attempts = 0, updated_at = now()
                         where id = :id and balance_state = 'none'
                        """)
                .param("id", bookingId)
                .param("cents", cents)
                .param("tax", taxCents)
                .param("due", ts(dueAt))
                .update();
    }

    @Override
    public List<DueBalance> dueBalances(Instant now, int limit) {
        return jdbc.sql("""
                        select b.id, coalesce(b.ref, '') as ref, b.merchant_id, b.customer_id,
                               coalesce(b.title, 'Job') as title, b.balance_cents, b.balance_tax_cents,
                               b.balance_attempts, b.starts_at
                          from booking.bookings b
                         where b.balance_state = 'pending' and b.balance_next_at <= :now
                           and b.state = 'confirmed' and b.customer_id is not null
                         order by b.balance_next_at, b.id
                         limit :limit
                        """)
                .param("now", ts(now))
                .param("limit", limit)
                .query((rs, _) -> new DueBalance(
                        rs.getString("id"),
                        rs.getString("ref"),
                        rs.getString("merchant_id"),
                        rs.getString("customer_id"),
                        rs.getString("title"),
                        rs.getLong("balance_cents"),
                        rs.getLong("balance_tax_cents"),
                        rs.getInt("balance_attempts"),
                        requiredInstant(rs, "starts_at")))
                .list();
    }

    @Override
    public void balanceHeld(String bookingId, String escrowId, Instant at) {
        jdbc.sql("""
                        update booking.bookings
                           set balance_state = 'held', balance_escrow_id = :escrow, balance_next_at = null,
                               balance_failure = null, updated_at = :at
                         where id = :id and balance_state in ('pending', 'failed')
                        """)
                .param("id", bookingId)
                .param("escrow", escrowId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void balanceFailed(String bookingId, String reason, @Nullable Instant retryAt) {
        jdbc.sql("""
                        update booking.bookings
                           set balance_attempts = balance_attempts + 1, balance_failure = :reason,
                               balance_next_at = :next,
                               balance_state = case when cast(:next as timestamptz) is null then 'failed' else 'pending' end,
                               updated_at = now()
                         where id = :id and balance_state in ('pending', 'failed')
                        """)
                .param("id", bookingId)
                .param("reason", reason)
                .param("next", retryAt == null ? null : ts(retryAt), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public void cancelled(String bookingId, String byUserId, long feeCents, long feeTaxCents, Instant at) {
        jdbc.sql("""
                        update booking.bookings
                           set cancelled_at = :at, cancelled_by = :by, cancel_fee_cents = :fee,
                               cancel_fee_tax_cents = :tax, balance_next_at = null,
                               balance_state = case when balance_state in ('pending', 'held') then 'cancelled'
                                                    else balance_state end,
                               updated_at = :at
                         where id = :id
                        """)
                .param("id", bookingId)
                .param("by", byUserId)
                .param("fee", feeCents)
                .param("tax", feeTaxCents)
                .param("at", ts(at))
                .update();
    }

    @Override
    public void policy(String bookingId, int windowHours, int feeBps, Instant freeCancelUntil) {
        jdbc.sql("""
                        update booking.bookings
                           set cancel_window_hours = :w, cancel_fee_bps = :f, free_cancel_until = :until
                         where id = :id
                        """)
                .param("id", bookingId)
                .param("w", windowHours)
                .param("f", feeBps)
                .param("until", ts(freeCancelUntil))
                .update();
    }

    private static Facts facts(ResultSet rs) throws SQLException {
        var cancelledAt = instant(rs, "cancelled_at");
        var customer = rs.getString("customer_id");
        return new Facts(
                rs.getString("id"),
                rs.getString("ref"),
                rs.getString("merchant_id"),
                customer,
                Objects.requireNonNullElse(rs.getString("state"), "confirmed"),
                rs.getString("title"),
                requiredInstant(rs, "starts_at"),
                rs.getLong("price"),
                rs.getLong("deposit"),
                rs.getLong("tax"),
                rs.getBoolean("paid"),
                rs.getString("quote_id"),
                rs.getObject("cancel_window_hours", Integer.class),
                rs.getObject("cancel_fee_bps", Integer.class),
                instant(rs, "free_cancel_until"),
                new Balance(
                        rs.getString("balance_state"),
                        rs.getLong("balance_cents"),
                        rs.getLong("balance_tax_cents"),
                        instant(rs, "balance_due_at"),
                        instant(rs, "balance_next_at"),
                        rs.getInt("balance_attempts"),
                        rs.getString("balance_failure")),
                cancelledAt == null
                        ? null
                        : new Cancellation(
                                cancelledAt,
                                rs.getLong("fee"),
                                rs.getLong("fee_tax"),
                                Objects.equals(rs.getString("cancelled_by"), customer)));
    }
}
