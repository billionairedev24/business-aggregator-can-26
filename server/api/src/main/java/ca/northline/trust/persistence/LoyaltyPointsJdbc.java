package ca.northline.trust.persistence;

import ca.northline.shared.JdbcTimes;
import ca.northline.trust.api.LoyaltyPoints;
import ca.northline.trust.api.PointsWallet;
import ca.northline.trust.application.PointsProperties;
import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link LoyaltyPoints} and {@link PointsWallet} over {@code trust.points_ledger} (V160 added {@code created_at} and the
 * user index; V360 one row per redemption reference).
 */
@Repository
@RequiredArgsConstructor
@EnableConfigurationProperties(PointsProperties.class)
class LoyaltyPointsJdbc implements LoyaltyPoints, PointsWallet {

    static final int WEEKS = 8;

    private final JdbcClient jdbc;
    private final Clock clock;
    private final PointsProperties properties;

    @Override
    public Settings settings() {
        return new Settings(properties.pointsPerDollar(), properties.maxOrderPercent(), properties.minRedeem());
    }

    @Override
    public long balance(String userId) {
        return Math.max(0, total(userId));
    }

    private long total(String userId) {
        return jdbc.sql("""
                        select coalesce(sum(delta), 0) from trust.points_ledger
                         where user_id = :u and (expires_at is null or expires_at > :now or delta < 0)
                        """)
                .param("u", userId)
                .param("now", JdbcTimes.ts(clock.instant()))
                .query(Long.class)
                .single();
    }

    @Override
    public boolean redeem(String userId, long points, String refId, String note) {
        return write(userId, -points, "redemption", refId, note);
    }

    @Override
    public boolean giveBack(String userId, long points, String refType, String refId, String note) {
        if (!refType.equals("redemption_return") && !refType.equals("refund_return")) {
            throw new IllegalArgumentException("not a give-back: " + refType);
        }
        return write(userId, points, refType, refId, note);
    }

    private boolean write(String userId, long delta, String refType, String refId, String note) {
        if (delta == 0) {
            return false;
        }
        return jdbc.sql("""
                        insert into trust.points_ledger (id, user_id, delta, ref_type, ref_id, note, created_at)
                        values (:id, :u, :delta, :type, :ref, :note, :at)
                        on conflict (user_id, ref_type, ref_id)
                           where ref_type in ('redemption', 'redemption_return', 'refund_return') do nothing
                        """)
                        .param("id", ca.northline.shared.Ids.next())
                        .param("u", userId)
                        .param("delta", Math.toIntExact(delta))
                        .param("type", refType)
                        .param("ref", refId)
                        .param("note", note)
                        .param("at", JdbcTimes.ts(clock.instant()))
                        .update()
                == 1;
    }

    @Override
    public Points of(String userId) {
        var now = clock.instant();
        var balance = jdbc.sql("""
                        select coalesce(sum(delta), 0) from trust.points_ledger
                         where user_id = :u and (expires_at is null or expires_at > :now or delta < 0)
                        """)
                .param("u", userId)
                .param("now", JdbcTimes.ts(now))
                .query(Long.class)
                .single();
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
        return new Points(Math.max(0, balance), weekly, properties.pointsPerDollar());
    }
}
