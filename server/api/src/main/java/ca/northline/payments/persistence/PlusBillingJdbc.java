package ca.northline.payments.persistence;

import ca.northline.payments.application.PlusScheduleStore;
import ca.northline.payments.domain.PlusPeriod;
import ca.northline.payments.domain.PlusSchedule;
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

/** {@link PlusScheduleStore} over {@code payments.plus_schedules}, {@code plus_periods} and {@code plus_attempts} (V482). */
@Repository
@RequiredArgsConstructor
class PlusBillingJdbc implements PlusScheduleStore {

    private static final String SCHEDULE = """
            select id, household_id, payer_id, plan, rrule, dtstart, price_cents, province, payment_method, next_plan,
                   next_rrule, next_price_cents, status, current_period_end, cancel_at_period_end, ended_reason, ended_at
              from payments.plus_schedules
            """;

    private static final String PERIOD = """
            select id, schedule_id, seq, starts_at, ends_at, plan, state, amount_cents, tax_cents, province,
                   tax_calculation, tax_transaction, payment_intent, attempts, next_attempt_at, failure, paid_at,
                   refund_id, refunded_at
              from payments.plus_periods
            """;

    private final JdbcClient jdbc;

    // ── schedules ───────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void insert(PlusSchedule s, Instant at) {
        jdbc.sql("""
                        insert into payments.plus_schedules (id, household_id, payer_id, plan, rrule, dtstart, price_cents,
                               province, payment_method, next_plan, next_rrule, next_price_cents, status,
                               current_period_end, cancel_at_period_end, ended_reason, ended_at, created_at, updated_at)
                        values (:id, :household, :payer, :plan, :rrule, :dtstart, :price, :province, :pm, :nextPlan,
                                :nextRule, :nextPrice, :status, :end, :cancel, :reason, :endedAt, :at, :at)
                        """).params(scheduleParams(s, at)).update();
    }

    @Override
    public void update(PlusSchedule s, Instant at) {
        jdbc.sql("""
                        update payments.plus_schedules
                           set plan = :plan, rrule = :rrule, dtstart = :dtstart, price_cents = :price, province = :province,
                               payment_method = :pm, next_plan = :nextPlan, next_rrule = :nextRule,
                               next_price_cents = :nextPrice, status = :status, current_period_end = :end,
                               cancel_at_period_end = :cancel, ended_reason = :reason, ended_at = :endedAt,
                               updated_at = :at
                         where id = :id
                        """).params(scheduleParams(s, at)).update();
    }

    private static java.util.Map<String, @Nullable Object> scheduleParams(PlusSchedule s, Instant at) {
        var p = new java.util.HashMap<String, @Nullable Object>();
        p.put("id", s.id());
        p.put("household", s.householdId());
        p.put("payer", s.payerId());
        p.put("plan", s.plan());
        p.put("rrule", s.rrule());
        p.put("dtstart", JdbcTimes.ts(s.dtstart()));
        p.put("price", s.priceCents());
        p.put("province", s.province());
        p.put("pm", s.paymentMethod());
        p.put("nextPlan", s.nextPlan());
        p.put("nextRule", s.nextRrule());
        p.put("nextPrice", s.nextPriceCents());
        p.put("status", s.status());
        p.put("end", JdbcTimes.ts(s.currentPeriodEnd()));
        p.put("cancel", s.cancelAtPeriodEnd());
        p.put("reason", s.endedReason());
        p.put("endedAt", JdbcTimes.ts(s.endedAt()));
        p.put("at", JdbcTimes.ts(at));
        return p;
    }

    @Override
    public Optional<PlusSchedule> schedule(String id) {
        return jdbc.sql(SCHEDULE + " where id = :id")
                .param("id", id)
                .query(PlusBillingJdbc::schedule)
                .optional();
    }

    @Override
    public Optional<PlusSchedule> lockSchedule(String id) {
        return jdbc.sql(SCHEDULE + " where id = :id for update")
                .param("id", id)
                .query(PlusBillingJdbc::schedule)
                .optional();
    }

    @Override
    public List<PlusSchedule> ofHousehold(String householdId) {
        return jdbc.sql(SCHEDULE + " where household_id = :h order by created_at desc, id desc")
                .param("h", householdId)
                .query(PlusBillingJdbc::schedule)
                .list();
    }

    private static PlusSchedule schedule(ResultSet rs, int row) throws SQLException {
        var nextPrice = rs.getLong("next_price_cents");
        return new PlusSchedule(
                rs.getString("id"),
                rs.getString("household_id"),
                rs.getString("payer_id"),
                rs.getString("plan"),
                rs.getString("rrule"),
                JdbcTimes.requiredInstant(rs, "dtstart"),
                rs.getLong("price_cents"),
                rs.getString("province"),
                rs.getString("payment_method"),
                rs.getString("next_plan"),
                rs.getString("next_rrule"),
                rs.wasNull() ? null : nextPrice,
                rs.getString("status"),
                JdbcTimes.requiredInstant(rs, "current_period_end"),
                rs.getBoolean("cancel_at_period_end"),
                rs.getString("ended_reason"),
                JdbcTimes.instant(rs, "ended_at"));
    }

    // ── periods ─────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public void insert(PlusPeriod p, Instant at) {
        jdbc.sql("""
                        insert into payments.plus_periods (id, schedule_id, seq, starts_at, ends_at, plan, state,
                               amount_cents, tax_cents, province, tax_calculation, tax_transaction, payment_intent,
                               attempts, next_attempt_at, failure, paid_at, refund_id, refunded_at, created_at, updated_at)
                        values (:id, :schedule, :seq, :starts, :ends, :plan, :state, :amount, :tax, :province, :calc,
                                :taxTx, :pi, :attempts, :next, :failure, :paid, :refund, :refunded, :at, :at)
                        """).params(periodParams(p, at)).update();
    }

    @Override
    public void update(PlusPeriod p, Instant at) {
        jdbc.sql("""
                        update payments.plus_periods
                           set ends_at = :ends, plan = :plan, state = :state, amount_cents = :amount, tax_cents = :tax,
                               province = :province, tax_calculation = :calc, tax_transaction = :taxTx,
                               payment_intent = :pi, attempts = :attempts, next_attempt_at = :next, failure = :failure,
                               paid_at = :paid, refund_id = :refund, refunded_at = :refunded, updated_at = :at
                         where id = :id
                        """).params(periodParams(p, at)).update();
    }

    private static java.util.Map<String, @Nullable Object> periodParams(PlusPeriod p, Instant at) {
        var m = new java.util.HashMap<String, @Nullable Object>();
        m.put("id", p.id());
        m.put("schedule", p.scheduleId());
        m.put("seq", p.seq());
        m.put("starts", JdbcTimes.ts(p.startsAt()));
        m.put("ends", JdbcTimes.ts(p.endsAt()));
        m.put("plan", p.plan());
        m.put("state", p.state());
        m.put("amount", p.amountCents());
        m.put("tax", p.taxCents());
        m.put("province", p.province());
        m.put("calc", p.taxCalculation());
        m.put("taxTx", p.taxTransaction());
        m.put("pi", p.paymentIntent());
        m.put("attempts", p.attempts());
        m.put("next", JdbcTimes.ts(p.nextAttemptAt()));
        m.put("failure", p.failure());
        m.put("paid", JdbcTimes.ts(p.paidAt()));
        m.put("refund", p.refundId());
        m.put("refunded", JdbcTimes.ts(p.refundedAt()));
        m.put("at", JdbcTimes.ts(at));
        return m;
    }

    @Override
    public Optional<PlusPeriod> period(String id) {
        return jdbc.sql(PERIOD + " where id = :id")
                .param("id", id)
                .query(PlusBillingJdbc::period)
                .optional();
    }

    @Override
    public Optional<PlusPeriod> lockPeriodIfFree(String id) {
        return jdbc.sql(PERIOD + " where id = :id for update skip locked")
                .param("id", id)
                .query(PlusBillingJdbc::period)
                .optional();
    }

    @Override
    public Optional<PlusPeriod> lockPeriod(String id) {
        return jdbc.sql(PERIOD + " where id = :id for update")
                .param("id", id)
                .query(PlusBillingJdbc::period)
                .optional();
    }

    @Override
    public Optional<PlusPeriod> open(String scheduleId) {
        return jdbc.sql(PERIOD + """
                         where schedule_id = :s and state in ('scheduled', 'retrying', 'requires_action')
                         order by seq desc limit 1
                        """)
                .param("s", scheduleId)
                .query(PlusBillingJdbc::period)
                .optional();
    }

    @Override
    public Optional<PlusPeriod> byPaymentIntent(String paymentIntent) {
        return jdbc.sql(PERIOD + " where payment_intent = :pi")
                .param("pi", paymentIntent)
                .query(PlusBillingJdbc::period)
                .optional();
    }

    @Override
    public List<PlusPeriod> periods(String scheduleId, int limit) {
        return jdbc.sql(PERIOD + " where schedule_id = :s order by seq desc limit :limit")
                .param("s", scheduleId)
                .param("limit", limit)
                .query(PlusBillingJdbc::period)
                .list();
    }

    @Override
    public List<String> due(Instant now, int limit) {
        return jdbc.sql("""
                        select id from payments.plus_periods
                         where state in ('scheduled', 'retrying', 'requires_action') and next_attempt_at <= :now
                         order by next_attempt_at, id limit :limit
                        """)
                .param("now", JdbcTimes.ts(now))
                .param("limit", limit)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public List<String> untaxed(int limit) {
        return jdbc.sql("""
                        select id from payments.plus_periods
                         where state = 'paid' and tax_transaction is null and tax_calculation is not null
                         order by paid_at limit :limit
                        """)
                .param("limit", limit)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    private static PlusPeriod period(ResultSet rs, int row) throws SQLException {
        return new PlusPeriod(
                rs.getString("id"),
                rs.getString("schedule_id"),
                rs.getInt("seq"),
                JdbcTimes.requiredInstant(rs, "starts_at"),
                JdbcTimes.requiredInstant(rs, "ends_at"),
                rs.getString("plan"),
                rs.getString("state"),
                rs.getLong("amount_cents"),
                rs.getLong("tax_cents"),
                rs.getString("province"),
                rs.getString("tax_calculation"),
                rs.getString("tax_transaction"),
                rs.getString("payment_intent"),
                rs.getInt("attempts"),
                JdbcTimes.instant(rs, "next_attempt_at"),
                rs.getString("failure"),
                JdbcTimes.instant(rs, "paid_at"),
                rs.getString("refund_id"),
                JdbcTimes.instant(rs, "refunded_at"));
    }

    // ── attempts ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean claimAttempt(String periodId, int attempt, Instant at, boolean offSession) {
        return jdbc.sql("""
                        insert into payments.plus_attempts (period_id, attempt, at, off_session, outcome)
                        values (:p, :n, :at, :off, 'pending') on conflict do nothing
                        """)
                        .param("p", periodId)
                        .param("n", attempt)
                        .param("at", JdbcTimes.ts(at))
                        .param("off", offSession)
                        .update()
                == 1;
    }

    @Override
    public void attemptOutcome(
            String periodId, int attempt, String outcome, @Nullable String code, @Nullable String paymentIntent) {
        jdbc.sql("""
                        update payments.plus_attempts set outcome = :o, code = :c, payment_intent = :pi
                         where period_id = :p and attempt = :n
                        """)
                .param("p", periodId)
                .param("n", attempt)
                .param("o", outcome)
                .param("c", code)
                .param("pi", paymentIntent)
                .update();
    }
}
