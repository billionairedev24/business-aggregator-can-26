package ca.northline.region.persistence;

import ca.northline.region.api.LoyaltyRules;
import ca.northline.shared.Ids;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link LoyaltyRules} over {@code region.loyalty_rules} (V422): the market's row, else its province's (the market's
 * parent, or the province by code), else the default row. Read on each call: a release or a checkout asks once.
 */
@Repository
@RequiredArgsConstructor
class LoyaltyRuleQueries implements LoyaltyRules {

    private static final String SELECT = """
            select l.id, l.region_id, r.name_i18n ->> 'en' as region_name, l.earn_points_per_dollar, l.earn_kinds,
                   l.expiry_months, l.referral_points, l.referral_monthly_cap, l.referral_min_order_cents, l.updated_at
              from region.loyalty_rules l left join region.regions r on r.id = l.region_id
            """;

    private final JdbcClient jdbc;

    @Override
    public Rule of(@Nullable String province, @Nullable String marketId) {
        // precedence: 1 the market's own row, 2 its province's (or the province's by code), 3 the default
        return jdbc.sql(SELECT + """
                         where l.region_id = cast(:market as text)
                            or l.region_id = (select m.parent_id from region.regions m
                                               where m.kind = 'market' and m.id = cast(:market as text))
                            or l.region_id = (select p.id from region.regions p
                                               where p.kind = 'province' and p.province = upper(cast(:province as text)))
                            or l.region_id is null
                         order by case when l.region_id = cast(:market as text) then 1
                                       when l.region_id is null then 3 else 2 end
                         limit 1
                        """)
                .param("market", marketId)
                .param("province", province == null ? null : province.strip().toUpperCase(Locale.ROOT))
                .query((rs, _) -> map(rs))
                .optional()
                .orElseGet(() -> new Rule("none", null, null, 0, Set.of(), null, 0, 0, 0, Instant.EPOCH));
    }

    @Override
    public List<Rule> all() {
        return jdbc.sql(SELECT + " order by l.region_id is not null, r.kind desc nulls first, region_name")
                .query((rs, _) -> map(rs))
                .list();
    }

    @Override
    public Optional<Rule> byRegion(@Nullable String regionId) {
        return jdbc.sql(SELECT + " where l.region_id is not distinct from cast(:r as text)")
                .param("r", regionId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public boolean regionExists(String regionId) {
        return jdbc.sql("select exists (select 1 from region.regions where id = :r and kind in ('province', 'market'))")
                .param("r", regionId)
                .query(Boolean.class)
                .single();
    }

    @Override
    public List<RegionChoice> regions() {
        return jdbc.sql("""
                        select id, kind, coalesce(name_i18n ->> 'en', city, province, id) as name, province
                          from region.regions where kind in ('province', 'market')
                         order by kind desc, province, sort nulls last, name
                        """)
                .query((rs, _) -> new RegionChoice(
                        rs.getString("id"), rs.getString("kind"), rs.getString("name"), rs.getString("province")))
                .list();
    }

    @Override
    public Rule save(@Nullable String regionId, Change c, String actorId, Instant at) {
        var params = new java.util.HashMap<String, Object>();
        params.put("id", regionId == null ? "loyalty-default" : Ids.next());
        params.put("region", regionId);
        params.put("ppd", c.pointsPerDollar());
        params.put("kinds", c.kinds().stream().sorted().toArray(String[]::new));
        params.put("expiry", c.expiryMonths());
        params.put("refPoints", c.referralPoints());
        params.put("refCap", c.referralMonthlyCap());
        params.put("refMin", c.referralMinOrderCents());
        params.put("at", JdbcTimes.ts(at));
        params.put("by", actorId);
        var updated = jdbc.sql("""
                        update region.loyalty_rules
                           set earn_points_per_dollar = :ppd, earn_kinds = :kinds, expiry_months = :expiry,
                               referral_points = :refPoints, referral_monthly_cap = :refCap,
                               referral_min_order_cents = :refMin, updated_at = :at, updated_by = :by
                         where region_id is not distinct from cast(:region as text)
                        """).params(params).update();
        if (updated == 0) {
            jdbc.sql("""
                            insert into region.loyalty_rules (id, region_id, earn_points_per_dollar, earn_kinds,
                                   expiry_months, referral_points, referral_monthly_cap, referral_min_order_cents,
                                   updated_at, updated_by)
                            values (:id, :region, :ppd, :kinds, :expiry, :refPoints, :refCap, :refMin, :at, :by)
                            """).params(params).update();
        }
        return byRegion(regionId).orElseThrow();
    }

    @Override
    public boolean remove(String regionId) {
        return jdbc.sql("delete from region.loyalty_rules where region_id = :r")
                        .param("r", regionId)
                        .update()
                > 0;
    }

    private static Rule map(ResultSet rs) throws SQLException {
        var kinds = rs.getArray("earn_kinds");
        var expiry = rs.getObject("expiry_months", Integer.class);
        return new Rule(
                rs.getString("id"),
                rs.getString("region_id"),
                rs.getString("region_name"),
                rs.getInt("earn_points_per_dollar"),
                kinds == null ? Set.of() : Set.copyOf(Arrays.asList((String[]) kinds.getArray())),
                expiry,
                rs.getInt("referral_points"),
                rs.getInt("referral_monthly_cap"),
                rs.getLong("referral_min_order_cents"),
                JdbcTimes.requiredInstant(rs, "updated_at"));
    }
}
