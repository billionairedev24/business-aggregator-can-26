package ca.northline.region.persistence;

import ca.northline.region.api.CancellationRules;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * {@link CancellationRules} over {@code region.regions.cancel_*} (V408): each value the market's, else its province's,
 * else the platform configuration's — like {@code KitchenVisitRuleQueries}.
 */
@Repository
class CancellationRuleQueries implements CancellationRules {

    private final JdbcClient jdbc;
    private final Rules platform;

    CancellationRuleQueries(
            JdbcClient jdbc,
            @Value("${northline.region.cancellation.window-hours:12}") int windowHours,
            @Value("${northline.region.cancellation.fee-bps:0}") int feeBps,
            @Value("${northline.region.cancellation.max-window-hours:72}") int maxWindowHours,
            @Value("${northline.region.cancellation.max-fee-bps:5000}") int maxFeeBps) {
        this.jdbc = jdbc;
        this.platform = new Rules(windowHours, feeBps, maxWindowHours, maxFeeBps);
    }

    @Override
    public Rules of(@Nullable String province, @Nullable String marketId) {
        return jdbc.sql("""
                        select coalesce(m.cancel_window_hours, mp.cancel_window_hours, p.cancel_window_hours) as w,
                               coalesce(m.cancel_fee_bps, mp.cancel_fee_bps, p.cancel_fee_bps) as f,
                               coalesce(m.cancel_max_window_hours, mp.cancel_max_window_hours,
                                        p.cancel_max_window_hours) as mw,
                               coalesce(m.cancel_max_fee_bps, mp.cancel_max_fee_bps, p.cancel_max_fee_bps) as mf
                          from (select 1) one
                          left join region.regions m on m.kind = 'market' and m.id = cast(:market as text)
                          left join region.regions mp on mp.kind = 'province' and mp.id = m.parent_id
                          left join region.regions p on p.kind = 'province' and m.id is null
                                                    and p.province = upper(cast(:province as text))
                        """)
                .param("market", marketId)
                .param("province", province)
                .query((rs, _) -> new Rules(
                        or(rs.getObject("w", Integer.class), platform.windowHours()),
                        or(rs.getObject("f", Integer.class), platform.feeBps()),
                        or(rs.getObject("mw", Integer.class), platform.maxWindowHours()),
                        or(rs.getObject("mf", Integer.class), platform.maxFeeBps())))
                .single();
    }

    private static int or(@Nullable Integer value, int fallback) {
        return value == null ? fallback : value;
    }
}
