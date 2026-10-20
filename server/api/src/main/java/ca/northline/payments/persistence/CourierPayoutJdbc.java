package ca.northline.payments.persistence;

import ca.northline.payments.application.CourierAccountGateway;
import ca.northline.payments.application.CourierPayoutStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link CourierPayoutStore} over {@code payments.courier_accounts} / {@code courier_payouts} (V387). */
@Repository
@RequiredArgsConstructor
class CourierPayoutJdbc implements CourierPayoutStore {

    private static final String ACCOUNT =
            "user_id, stripe_account, payouts_enabled, requirements_due, external_ref, bank_last4, created_at";
    private static final String PAYOUT = """
            id, user_id, stripe_account, amount_cents, stripe_transfer, stripe_payout, state, arrives_at, failure,
            created_at""";

    private final JdbcClient jdbc;

    @Override
    public Optional<CourierAccount> account(String courierUserId) {
        return jdbc.sql("select " + ACCOUNT + " from payments.courier_accounts where user_id = :u")
                .param("u", courierUserId)
                .query(CourierPayoutJdbc::account)
                .optional();
    }

    @Override
    public Optional<CourierAccount> byStripeAccount(String stripeAccount) {
        return jdbc.sql("select " + ACCOUNT + " from payments.courier_accounts where stripe_account = :a")
                .param("a", stripeAccount)
                .query(CourierPayoutJdbc::account)
                .optional();
    }

    @Override
    public boolean insert(CourierAccount a) {
        return jdbc.sql("""
                                insert into payments.courier_accounts (user_id, stripe_account, payouts_enabled,
                                       requirements_due, external_ref, bank_last4, created_at, updated_at)
                                values (:u, :a, :enabled, :due, :ref, :last4, :at, :at)
                                on conflict (user_id) do nothing
                                """)
                        .param("u", a.userId())
                        .param("a", a.stripeAccount())
                        .param("enabled", a.payoutsEnabled())
                        .param("due", a.requirementsDue())
                        .param("ref", a.externalRef())
                        .param("last4", a.bankLast4())
                        .param("at", JdbcTimes.ts(a.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                        .update()
                == 1;
    }

    @Override
    public void updateAccount(String stripeAccount, CourierAccountGateway.Account s, Instant at) {
        jdbc.sql("""
                        update payments.courier_accounts
                           set payouts_enabled = :enabled, requirements_due = :due,
                               external_ref = coalesce(:ref, external_ref), bank_last4 = coalesce(:last4, bank_last4),
                               last_event_at = :at, updated_at = :at
                         where stripe_account = :a and (last_event_at is null or last_event_at <= :at)
                        """)
                .param("a", stripeAccount)
                .param("enabled", s.payoutsEnabled())
                .param("due", s.requirementsDue())
                .param("ref", s.externalRef())
                .param("last4", s.bankLast4())
                .param("at", JdbcTimes.ts(at), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public List<CourierAccount> payable() {
        return jdbc.sql("select " + ACCOUNT + " from payments.courier_accounts where payouts_enabled order by user_id")
                .query(CourierPayoutJdbc::account)
                .list();
    }

    @Override
    public void insertPayout(CourierPayout p) {
        jdbc.sql("""
                        insert into payments.courier_payouts (id, user_id, stripe_account, amount_cents, stripe_transfer,
                               stripe_payout, state, arrives_at, failure, created_at, updated_at)
                        values (:id, :u, :a, :amount, :tr, :po, :state, :arrives, :failure, :at, :at)
                        """)
                .param("id", p.id())
                .param("u", p.userId())
                .param("a", p.stripeAccount())
                .param("amount", p.amountCents())
                .param("tr", p.stripeTransfer())
                .param("po", p.stripePayout())
                .param("state", p.state())
                .param("arrives", JdbcTimes.ts(p.arrivesAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failure", p.failure())
                .param("at", JdbcTimes.ts(p.createdAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .update();
    }

    @Override
    public void updatePayout(CourierPayout p) {
        jdbc.sql("""
                        update payments.courier_payouts
                           set stripe_transfer = :tr, stripe_payout = :po, state = :state, arrives_at = :arrives,
                               failure = :failure, updated_at = now()
                         where id = :id
                        """)
                .param("id", p.id())
                .param("tr", p.stripeTransfer())
                .param("po", p.stripePayout())
                .param("state", p.state())
                .param("arrives", JdbcTimes.ts(p.arrivesAt()), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("failure", p.failure())
                .update();
    }

    @Override
    public Optional<CourierPayout> payout(String id) {
        return jdbc.sql("select " + PAYOUT + " from payments.courier_payouts where id = :id")
                .param("id", id)
                .query(CourierPayoutJdbc::payout)
                .optional();
    }

    @Override
    public Optional<CourierPayout> byStripePayout(String stripePayout) {
        return jdbc.sql("select " + PAYOUT + " from payments.courier_payouts where stripe_payout = :po for update")
                .param("po", stripePayout)
                .query(CourierPayoutJdbc::payout)
                .optional();
    }

    @Override
    public List<CourierPayout> arrivedBy(Instant before, int limit) {
        return jdbc.sql("select " + PAYOUT + """
                         from payments.courier_payouts
                        where state = 'in_transit' and arrives_at <= :before
                        order by arrives_at limit :limit for update skip locked
                        """)
                .param("before", JdbcTimes.ts(before), Types.TIMESTAMP_WITH_TIMEZONE)
                .param("limit", limit)
                .query(CourierPayoutJdbc::payout)
                .list();
    }

    @Override
    public List<CourierPayout> payouts(String courierUserId, int limit) {
        return jdbc.sql(
                        "select " + PAYOUT
                                + " from payments.courier_payouts where user_id = :u order by created_at desc, id desc limit :n")
                .param("u", courierUserId)
                .param("n", limit)
                .query(CourierPayoutJdbc::payout)
                .list();
    }

    private static CourierAccount account(ResultSet rs, int row) throws SQLException {
        return new CourierAccount(
                rs.getString("user_id"),
                rs.getString("stripe_account"),
                rs.getBoolean("payouts_enabled"),
                rs.getInt("requirements_due"),
                rs.getString("external_ref"),
                rs.getString("bank_last4"),
                JdbcTimes.requiredInstant(rs, "created_at"));
    }

    private static CourierPayout payout(ResultSet rs, int row) throws SQLException {
        return new CourierPayout(
                rs.getString("id"),
                rs.getString("user_id"),
                rs.getString("stripe_account"),
                rs.getLong("amount_cents"),
                rs.getString("stripe_transfer"),
                rs.getString("stripe_payout"),
                rs.getString("state"),
                JdbcTimes.instant(rs, "arrives_at"),
                rs.getString("failure"),
                JdbcTimes.requiredInstant(rs, "created_at"));
    }
}
