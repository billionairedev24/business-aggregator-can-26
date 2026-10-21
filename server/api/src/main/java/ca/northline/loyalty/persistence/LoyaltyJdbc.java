package ca.northline.loyalty.persistence;

import ca.northline.loyalty.application.LoyaltyStore;
import ca.northline.loyalty.domain.MembershipState;
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

/** {@link LoyaltyStore} over schema {@code loyalty} (V424). */
@Repository
@RequiredArgsConstructor
class LoyaltyJdbc implements LoyaltyStore {

    private final JdbcClient jdbc;

    // ── earnings ────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public boolean insert(EarningRow e) {
        return jdbc.sql("""
                        insert into loyalty.earnings (escrow_id, customer_id, merchant_id, kind, ref_type, ref_id,
                               amount_cents, rule_id, points_per_dollar, plus_multiplier_pct, points, reward_points,
                               expires_at, created_at, excluded_refund_ids)
                        values (:escrow, :customer, :merchant, :kind, :refType, :refId, :amount, :rule, :ppd, :plus,
                                :points, :reward, :expires, :at, :excluded)
                        on conflict (escrow_id) do nothing
                        """)
                        .param("escrow", e.escrowId())
                        .param("customer", e.customerId())
                        .param("merchant", e.merchantId())
                        .param("kind", e.kind())
                        .param("refType", e.refType())
                        .param("refId", e.refId())
                        .param("amount", e.amountCents())
                        .param("rule", e.ruleId())
                        .param("ppd", e.pointsPerDollar())
                        .param("plus", e.plusMultiplierPct())
                        .param("points", Math.toIntExact(e.points()))
                        .param("reward", Math.toIntExact(e.rewardPoints()))
                        .param("expires", JdbcTimes.ts(e.expiresAt()))
                        .param("at", JdbcTimes.ts(e.createdAt()))
                        .param("excluded", e.excludedRefundIds().toArray(String[]::new))
                        .update()
                == 1;
    }

    @Override
    public Optional<EarningRow> lockEarning(String escrowId) {
        return jdbc.sql("select * from loyalty.earnings where escrow_id = :e for update")
                .param("e", escrowId)
                .query((rs, _) -> new EarningRow(
                        rs.getString("escrow_id"),
                        rs.getString("customer_id"),
                        rs.getString("merchant_id"),
                        rs.getString("kind"),
                        rs.getString("ref_type"),
                        rs.getString("ref_id"),
                        rs.getLong("amount_cents"),
                        rs.getString("rule_id"),
                        rs.getInt("points_per_dollar"),
                        rs.getInt("plus_multiplier_pct"),
                        rs.getLong("points"),
                        rs.getLong("reward_points"),
                        rs.getLong("reversed_cents"),
                        rs.getLong("reversed_points"),
                        JdbcTimes.instant(rs, "expires_at"),
                        JdbcTimes.requiredInstant(rs, "created_at"),
                        java.util.Arrays.asList(
                                (String[]) rs.getArray("excluded_refund_ids").getArray())))
                .optional();
    }

    @Override
    public void reversed(String escrowId, long cents, long points) {
        jdbc.sql("""
                        update loyalty.earnings set reversed_cents = least(amount_cents, reversed_cents + :c),
                               reversed_points = least(points, reversed_points + :p)
                         where escrow_id = :e
                        """).param("e", escrowId).param("c", cents).param("p", points).update();
    }

    @Override
    public void rewardPoints(String escrowId, long points) {
        jdbc.sql("update loyalty.earnings set reward_points = :p where escrow_id = :e")
                .param("e", escrowId)
                .param("p", Math.toIntExact(points))
                .update();
    }

    @Override
    public boolean hasEarned(String customerId) {
        return jdbc.sql("select exists (select 1 from loyalty.earnings where customer_id = :c)")
                .param("c", customerId)
                .query(Boolean.class)
                .single();
    }

    // ── referrals ───────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<String> codeOf(String userId) {
        return jdbc.sql("select code from loyalty.referral_codes where user_id = :u")
                .param("u", userId)
                .query(String.class)
                .optional();
    }

    @Override
    public boolean insertCode(String userId, String code, Instant at) {
        return jdbc.sql("""
                        insert into loyalty.referral_codes (user_id, code, created_at) values (:u, :code, :at)
                        on conflict do nothing
                        """)
                        .param("u", userId)
                        .param("code", code)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public Optional<String> ownerOf(String code) {
        return jdbc.sql("select user_id from loyalty.referral_codes where code = :code")
                .param("code", code)
                .query(String.class)
                .optional();
    }

    @Override
    public boolean insert(Referral r) {
        return jdbc.sql("""
                        insert into loyalty.referrals (invitee_id, referrer_id, code, state, claimed_at)
                        values (:invitee, :referrer, :code, :state, :at)
                        on conflict (invitee_id) do nothing
                        """)
                        .param("invitee", r.inviteeId())
                        .param("referrer", r.referrerId())
                        .param("code", r.code())
                        .param("state", r.state())
                        .param("at", JdbcTimes.ts(r.claimedAt()))
                        .update()
                == 1;
    }

    @Override
    public Optional<Referral> lockReferral(String inviteeId) {
        return jdbc.sql("select * from loyalty.referrals where invitee_id = :i for update")
                .param("i", inviteeId)
                .query((rs, _) -> new Referral(
                        rs.getString("invitee_id"),
                        rs.getString("referrer_id"),
                        rs.getString("code"),
                        rs.getString("state"),
                        JdbcTimes.requiredInstant(rs, "claimed_at"),
                        JdbcTimes.instant(rs, "decided_at"),
                        rs.getString("escrow_id"),
                        rs.getLong("points"),
                        rs.getString("refused_reason")))
                .optional();
    }

    @Override
    public void decide(
            String inviteeId,
            String state,
            @Nullable String reason,
            @Nullable String escrowId,
            long points,
            Instant at) {
        jdbc.sql("""
                        update loyalty.referrals set state = :state, refused_reason = :reason, escrow_id = :escrow,
                               points = :points, decided_at = :at
                         where invitee_id = :i and state = 'pending'
                        """)
                .param("i", inviteeId)
                .param("state", state)
                .param("reason", reason)
                .param("escrow", escrowId)
                .param("points", Math.toIntExact(points))
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public long rewardedSince(String referrerId, Instant since) {
        return jdbc.sql("""
                        select count(*) from loyalty.referrals
                         where referrer_id = :r and state = 'rewarded' and decided_at >= :since
                        """)
                .param("r", referrerId)
                .param("since", JdbcTimes.ts(since))
                .query(Long.class)
                .single();
    }

    @Override
    public ReferralTotals totals(String referrerId) {
        return jdbc.sql("""
                        select count(*) filter (where state = 'pending') as pending,
                               count(*) filter (where state = 'rewarded') as rewarded,
                               coalesce(sum(points) filter (where state = 'rewarded'), 0) as points
                          from loyalty.referrals where referrer_id = :r
                        """)
                .param("r", referrerId)
                .query((rs, _) ->
                        new ReferralTotals(rs.getLong("pending"), rs.getLong("rewarded"), rs.getLong("points")))
                .single();
    }

    @Override
    public List<String> pendingClaimedBefore(Instant before, int limit) {
        return jdbc.sql("""
                        select invitee_id from loyalty.referrals where state = 'pending' and claimed_at < :before
                         order by claimed_at limit :n
                        """)
                .param("before", JdbcTimes.ts(before))
                .param("n", limit)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public long attemptsSince(String userId, Instant since) {
        return jdbc.sql("select count(*) from loyalty.referral_attempts where user_id = :u and at >= :since")
                .param("u", userId)
                .param("since", JdbcTimes.ts(since))
                .query(Long.class)
                .single();
    }

    @Override
    public void attempt(String userId, Instant at) {
        jdbc.sql("insert into loyalty.referral_attempts (user_id, at) values (:u, :at)")
                .param("u", userId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    // ── Plus ────────────────────────────────────────────────────────────────────────────────────────────────────

    @Override
    public Optional<MembershipState> membership(String householdId) {
        return jdbc.sql("select * from loyalty.memberships where household_id = :h")
                .param("h", householdId)
                .query((rs, _) -> membership(rs))
                .optional();
    }

    @Override
    public Optional<MembershipState> lockMembership(String householdId) {
        return jdbc.sql("select * from loyalty.memberships where household_id = :h for update")
                .param("h", householdId)
                .query((rs, _) -> membership(rs))
                .optional();
    }

    @Override
    public Optional<MembershipState> bySubscription(String subscriptionId) {
        return jdbc.sql("select * from loyalty.memberships where subscription_id = :s for update")
                .param("s", subscriptionId)
                .query((rs, _) -> membership(rs))
                .optional();
    }

    @Override
    public Optional<MembershipState> ofPayer(String userId) {
        return jdbc.sql("select * from loyalty.memberships where payer_id = :u order by updated_at desc limit 1")
                .param("u", userId)
                .query((rs, _) -> membership(rs))
                .optional();
    }

    private static MembershipState membership(ResultSet rs) throws SQLException {
        return new MembershipState(
                rs.getString("household_id"),
                rs.getString("payer_id"),
                rs.getString("plan"),
                rs.getString("status"),
                rs.getString("provider"),
                rs.getString("subscription_id"),
                JdbcTimes.instant(rs, "current_period_end"),
                rs.getBoolean("cancel_at_period_end"),
                rs.getBoolean("trial_used"),
                JdbcTimes.requiredInstant(rs, "started_at"),
                JdbcTimes.instant(rs, "canceled_at"),
                JdbcTimes.instant(rs, "last_event_at"));
    }

    @Override
    public void save(MembershipState m, Instant at) {
        jdbc.sql("""
                        insert into loyalty.memberships (household_id, payer_id, plan, status, provider, subscription_id,
                               current_period_end, cancel_at_period_end, trial_used, started_at, canceled_at, updated_at,
                               last_event_at)
                        values (:h, :payer, :plan, :status, :provider, :sub, :end, :cancel, :trial, :started, :canceled,
                                :at, :event)
                        on conflict (household_id) do update set payer_id = excluded.payer_id, plan = excluded.plan,
                          status = excluded.status, provider = excluded.provider,
                          subscription_id = excluded.subscription_id, current_period_end = excluded.current_period_end,
                          cancel_at_period_end = excluded.cancel_at_period_end, trial_used = excluded.trial_used,
                          started_at = excluded.started_at, canceled_at = excluded.canceled_at,
                          updated_at = excluded.updated_at, last_event_at = excluded.last_event_at
                        """)
                .param("h", m.householdId())
                .param("payer", m.payerId())
                .param("plan", m.plan())
                .param("status", m.status())
                .param("provider", m.provider())
                .param("sub", m.subscriptionId())
                .param("end", JdbcTimes.ts(m.currentPeriodEnd()))
                .param("cancel", m.cancelAtPeriodEnd())
                .param("trial", m.trialUsed())
                .param("started", JdbcTimes.ts(m.startedAt()))
                .param("canceled", JdbcTimes.ts(m.canceledAt()))
                .param("at", JdbcTimes.ts(at))
                .param("event", JdbcTimes.ts(m.lastEventAt()))
                .update();
    }

    @Override
    public List<MembershipState> legacy(int limit) {
        return jdbc.sql("""
                        select * from loyalty.memberships
                         where subscription_id like 'sub\\_%' and status in ('trialing', 'active', 'past_due')
                         order by current_period_end limit :n
                        """).param("n", limit).query((rs, _) -> membership(rs)).list();
    }

    @Override
    public PlusCounts counts() {
        return jdbc.sql("""
                        select count(*) filter (where status in ('trialing', 'active', 'past_due')) as members,
                               count(*) filter (where status = 'trialing') as trialing
                          from loyalty.memberships
                        """)
                .query((rs, _) -> new PlusCounts(rs.getLong("members"), rs.getLong("trialing")))
                .single();
    }

    @Override
    public long pendingReferrals() {
        return jdbc.sql("select count(*) from loyalty.referrals where state = 'pending'")
                .query(Long.class)
                .single();
    }
}
