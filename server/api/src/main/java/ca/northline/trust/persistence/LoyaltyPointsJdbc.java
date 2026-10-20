package ca.northline.trust.persistence;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.trust.api.LoyaltyPoints;
import ca.northline.trust.api.PointsLedger;
import ca.northline.trust.api.PointsWallet;
import ca.northline.trust.application.PointsProperties;
import ca.northline.trust.domain.PointsBalance;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link LoyaltyPoints}, {@link PointsWallet} and {@link PointsLedger} over {@code trust.points_ledger} (V160 added
 * {@code created_at} and the user index; V360 and V426 one row per reference). Balances are computed from the person's
 * rows by {@link PointsBalance} (first-to-expire spent first); the expiry job writes what expired as {@code expiry}
 * rows so the activity shows it and Σ delta matches the balance.
 */
@Repository
@RequiredArgsConstructor
@EnableConfigurationProperties(PointsProperties.class)
class LoyaltyPointsJdbc implements LoyaltyPoints, PointsWallet, PointsLedger {

    static final int WEEKS = 8;

    /** Reference types written at most once per person and reference (V360 and V426's unique indexes). */
    private static final Set<String> ONCE = Set.of(
            "redemption",
            "redemption_return",
            "refund_return",
            EARN,
            EARN_REVERSAL,
            REWARD,
            REWARD_REVERSAL,
            REFERRAL,
            PointsBalance.EXPIRY);

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PointsProperties properties;

    @Override
    public Settings settings() {
        return new Settings(properties.pointsPerDollar(), properties.maxOrderPercent(), properties.minRedeem());
    }

    @Override
    public long balance(String userId) {
        return computed(userId, clock.instant()).available();
    }

    private PointsBalance.Result computed(String userId, Instant now) {
        return PointsBalance.of(rows(userId), now);
    }

    private List<PointsBalance.Row> rows(String userId) {
        return jdbc.sql("""
                        select id, delta, coalesce(ref_type, '') as ref_type, coalesce(created_at, now()) as at,
                               expires_at
                          from trust.points_ledger where user_id = :u
                        """)
                .param("u", userId)
                .query((rs, _) -> new PointsBalance.Row(
                        rs.getString("id"),
                        rs.getLong("delta"),
                        rs.getString("ref_type"),
                        JdbcTimes.requiredInstant(rs, "at"),
                        JdbcTimes.instant(rs, "expires_at")))
                .list();
    }

    @Override
    public boolean redeem(String userId, long points, String refId, String note) {
        return write(userId, -points, "redemption", refId, note, null, null);
    }

    @Override
    public boolean giveBack(String userId, long points, String refType, String refId, String note) {
        if (!refType.equals("redemption_return") && !refType.equals("refund_return")) {
            throw new IllegalArgumentException("not a give-back: " + refType);
        }
        return write(userId, points, refType, refId, note, null, null);
    }

    @Override
    public boolean credit(
            String userId,
            long points,
            String refType,
            String refId,
            @Nullable Instant expiresAt,
            @Nullable String fundedBy,
            String note) {
        if (!refType.equals(EARN) && !refType.equals(REWARD) && !refType.equals(REFERRAL)) {
            throw new IllegalArgumentException("not a credit: " + refType);
        }
        return points > 0 && write(userId, points, refType, refId, note, expiresAt, fundedBy);
    }

    @Override
    public boolean takeBack(String userId, long points, String refType, String refId, String note) {
        if (!refType.equals(EARN_REVERSAL) && !refType.equals(REWARD_REVERSAL)) {
            throw new IllegalArgumentException("not a take-back: " + refType);
        }
        return points > 0 && write(userId, -points, refType, refId, note, null, null);
    }

    private boolean write(
            String userId,
            long delta,
            String refType,
            String refId,
            String note,
            @Nullable Instant expiresAt,
            @Nullable String fundedBy) {
        if (delta == 0 && !refType.equals(PointsBalance.EXPIRY)) {
            return false;
        }
        if (!ONCE.contains(refType)) {
            throw new IllegalArgumentException("unknown points reference type: " + refType);
        }
        // the conflict target names no index: V360's and V426's partial unique indexes both cover their types
        return jdbc.sql("""
                        insert into trust.points_ledger (id, user_id, delta, ref_type, ref_id, note, created_at,
                                                         expires_at, funded_by_merchant_id)
                        values (:id, :u, :delta, :type, :ref, :note, :at, :expires, :funded)
                        on conflict do nothing
                        """)
                        .param("id", Ids.next())
                        .param("u", userId)
                        .param("delta", Math.toIntExact(delta))
                        .param("type", refType)
                        .param("ref", refId)
                        .param("note", note)
                        .param("at", JdbcTimes.ts(clock.instant()))
                        .param("expires", JdbcTimes.ts(expiresAt))
                        .param("funded", fundedBy)
                        .update()
                == 1;
    }

    @Override
    public List<Activity> activity(String userId, int limit) {
        return jdbc.sql("""
                        select id, coalesce(ref_type, 'earn') as ref_type, delta, note, coalesce(created_at, now()) as at,
                               expires_at, funded_by_merchant_id
                          from trust.points_ledger where user_id = :u and delta <> 0
                         order by created_at desc nulls last, id desc limit :limit
                        """)
                .param("u", userId)
                .param("limit", limit)
                .query((rs, _) -> new Activity(
                        rs.getString("id"),
                        rs.getString("ref_type"),
                        rs.getLong("delta"),
                        rs.getString("note"),
                        JdbcTimes.requiredInstant(rs, "at"),
                        JdbcTimes.instant(rs, "expires_at"),
                        rs.getString("funded_by_merchant_id")))
                .list();
    }

    @Override
    public Summary summary(String userId) {
        var result = computed(userId, clock.instant());
        var next = result.nextExpiry();
        return new Summary(result.available(), next == null ? null : next.at(), next == null ? 0 : next.points());
    }

    @Override
    public int expire(Instant now, int people) {
        var due = jdbc.sql("""
                        select distinct c.user_id from trust.points_ledger c
                         where c.delta > 0 and c.expires_at is not null and c.expires_at <= :now
                           and not exists (select 1 from trust.points_ledger e
                                            where e.user_id = c.user_id and e.ref_type = 'expiry' and e.ref_id = c.id)
                         limit :n
                        """)
                .param("now", JdbcTimes.ts(now))
                .param("n", people)
                .query((rs, _) -> java.util.Objects.requireNonNull(rs.getString(1)))
                .list();
        for (var user : due) {
            // one person at a time under their lock: a checkout spending their points waits
            jdbc.sql("select pg_advisory_xact_lock(hashtext('points:' || :u))")
                    .param("u", user)
                    .query((rs, _) -> 1)
                    .list();
            PointsBalance.of(rows(user), now)
                    .expired()
                    .forEach((creditId, left) ->
                            write(user, -left, PointsBalance.EXPIRY, creditId, "Expired", null, null));
        }
        return due.size();
    }

    @Override
    public Outstanding outstanding() {
        return jdbc.sql("""
                        select coalesce(sum(b), 0) as points, count(*) filter (where b > 0) as holders
                          from (select greatest(sum(delta), 0) as b from trust.points_ledger group by user_id) t
                        """)
                .query((rs, _) -> new Outstanding(rs.getLong("points"), rs.getLong("holders")))
                .single();
    }

    @Override
    public Points of(String userId) {
        var now = clock.instant();
        var balance = computed(userId, now).available();
        var weekly = new ArrayList<Long>();
        var start = now.minus(Duration.ofDays(7L * WEEKS));
        var earned = jdbc.sql("""
                        select floor(extract(epoch from (created_at - :start)) / 604800)::int as week, sum(delta) as pts
                          from trust.points_ledger
                         where user_id = :u and delta > 0 and created_at > :start and created_at <= :now
                         group by 1
                        """)
                .param("u", userId)
                .param("start", JdbcTimes.ts(start))
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> new long[] {rs.getInt("week"), rs.getLong("pts")})
                .list();
        for (int i = 0; i < WEEKS; i++) {
            weekly.add(0L);
        }
        earned.forEach(w -> {
            var week = (int) Math.min(WEEKS - 1, Math.max(0, w[0]));
            weekly.set(week, weekly.get(week) + w[1]);
        });
        return new Points(balance, weekly, properties.pointsPerDollar());
    }
}
