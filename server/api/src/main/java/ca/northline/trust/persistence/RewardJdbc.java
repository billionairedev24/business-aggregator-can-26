package ca.northline.trust.persistence;

import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import ca.northline.trust.application.RewardStore;
import ca.northline.trust.domain.MerchantReward;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link RewardStore} over {@code trust.merchant_rewards} (S-75: one row per business, upserted; S-152: budget, spent,
 * listings) and {@code trust.reward_credits} (S-152).
 */
@Repository
@RequiredArgsConstructor
class RewardJdbc implements RewardStore {

    private static final String SELECT = """
            select merchant_id, active, coalesce(multiplier, 2) as multiplier, label, ends_on, updated_at, budget_cents,
                   coalesce(spent_cents, 0) as spent_cents, listing_ids, started_at, exhausted_at
              from trust.merchant_rewards
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<MerchantReward> find(String merchantId) {
        return jdbc.sql(SELECT + " where merchant_id = :m and ends_on is not null")
                .param("m", merchantId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public Optional<MerchantReward> lock(String merchantId) {
        return jdbc.sql(SELECT + " where merchant_id = :m and ends_on is not null for update")
                .param("m", merchantId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    private static MerchantReward map(ResultSet rs) throws SQLException {
        var listings = rs.getArray("listing_ids");
        return new MerchantReward(
                rs.getString("merchant_id"),
                rs.getBoolean("active"),
                rs.getInt("multiplier"),
                rs.getString("label"),
                rs.getObject("ends_on", LocalDate.class),
                JdbcTimes.requiredInstant(rs, "updated_at"),
                rs.getObject("budget_cents", Long.class),
                rs.getLong("spent_cents"),
                listings == null ? List.of() : Arrays.asList((String[]) listings.getArray()),
                JdbcTimes.instant(rs, "started_at"),
                JdbcTimes.instant(rs, "exhausted_at"));
    }

    @Override
    public void save(MerchantReward r, String actorId) {
        jdbc.sql("""
                        insert into trust.merchant_rewards (id, merchant_id, scope, multiplier, active, label, ends_on,
                                                            updated_at, updated_by, budget_cents, spent_cents,
                                                            listing_ids, started_at, exhausted_at)
                        values (:id, :m, '"all"'::jsonb, :multiplier, :active, :label, :endsOn, :at, :by, :budget, 0,
                                :listings, :started, null)
                        on conflict (merchant_id) do update set multiplier = excluded.multiplier, active = excluded.active,
                          label = excluded.label, ends_on = excluded.ends_on, updated_at = excluded.updated_at,
                          updated_by = excluded.updated_by, budget_cents = excluded.budget_cents,
                          listing_ids = excluded.listing_ids, started_at = excluded.started_at,
                          exhausted_at = case when excluded.budget_cents is distinct from
                                                   trust.merchant_rewards.budget_cents
                                              then null else trust.merchant_rewards.exhausted_at end
                        """)
                .param("id", Ids.next())
                .param("m", r.merchantId())
                .param("multiplier", r.multiplier())
                .param("active", r.active())
                .param("label", r.label())
                .param("endsOn", r.endsOn())
                .param("at", JdbcTimes.ts(r.updatedAt()))
                .param("by", actorId)
                .param("budget", r.budgetCents())
                .param("listings", r.listingIds().toArray(String[]::new))
                .param("started", JdbcTimes.ts(r.startedAt()))
                .update();
    }

    @Override
    public void spend(String merchantId, long cents, Instant at) {
        jdbc.sql("""
                        update trust.merchant_rewards
                           set spent_cents = greatest(0, coalesce(spent_cents, 0) + :c),
                               exhausted_at = case when budget_cents - greatest(0, coalesce(spent_cents, 0) + :c) <= 0
                                                   then coalesce(exhausted_at, :at) else null end
                         where merchant_id = :m
                        """)
                .param("m", merchantId)
                .param("c", cents)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public boolean insert(Credit c) {
        return jdbc.sql("""
                        insert into trust.reward_credits (id, merchant_id, user_id, escrow_id, multiplier, points, cents,
                                                          created_at)
                        values (:id, :m, :u, :e, :mult, :points, :cents, :at)
                        on conflict (escrow_id) do nothing
                        """)
                        .param("id", c.id())
                        .param("m", c.merchantId())
                        .param("u", c.userId())
                        .param("e", c.escrowId())
                        .param("mult", c.multiplier())
                        .param("points", Math.toIntExact(c.points()))
                        .param("cents", c.cents())
                        .param("at", JdbcTimes.ts(c.createdAt()))
                        .update()
                == 1;
    }

    @Override
    public Optional<Credit> creditOf(String escrowId) {
        return jdbc.sql("""
                        select id, merchant_id, user_id, escrow_id, multiplier, points, cents, reversed_points,
                               reversed_cents, created_at
                          from trust.reward_credits where escrow_id = :e for update
                        """)
                .param("e", escrowId)
                .query((rs, _) -> new Credit(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("user_id"),
                        rs.getString("escrow_id"),
                        rs.getInt("multiplier"),
                        rs.getLong("points"),
                        rs.getLong("cents"),
                        rs.getLong("reversed_points"),
                        rs.getLong("reversed_cents"),
                        JdbcTimes.requiredInstant(rs, "created_at")))
                .optional();
    }

    @Override
    public void reversed(String creditId, long points, long cents) {
        jdbc.sql("""
                        update trust.reward_credits
                           set reversed_points = reversed_points + :p, reversed_cents = reversed_cents + :c
                         where id = :id
                        """).param("id", creditId).param("p", points).param("c", cents).update();
    }

    @Override
    public List<MerchantReward> running() {
        return jdbc.sql(SELECT + """
                         where active and ends_on is not null and exhausted_at is null and budget_cents is not null
                           and ends_on >= current_date - 1
                         order by updated_at desc limit 500
                        """).query((rs, _) -> map(rs)).list();
    }

    @Override
    public Totals totals(String merchantId) {
        return jdbc.sql("""
                        select coalesce(sum(cents - reversed_cents), 0) as cents,
                               coalesce(sum(points - reversed_points), 0) as points, count(*) as sales
                          from trust.reward_credits where merchant_id = :m
                        """)
                .param("m", merchantId)
                .query((rs, _) -> new Totals(rs.getLong("cents"), rs.getLong("points"), rs.getLong("sales")))
                .single();
    }
}
