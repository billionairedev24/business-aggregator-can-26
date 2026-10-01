package ca.northline.food.persistence;

import ca.northline.food.application.AutoPauseStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link AutoPauseStore}: late = a cooking ticket past its ready-by time, as KitchenCalendarJdbc counts it. */
@Repository
@RequiredArgsConstructor
class AutoPauseJdbc implements AutoPauseStore {

    private static final String SELECT = """
            select s.merchant_id, s.auto_pause_late, s.auto_paused_at,
                   (select count(*) from food.kitchen_tickets t
                     where t.merchant_id = s.merchant_id and t.stage = 'cooking' and t.ready_by < :now) as late
              from food.kitchen_settings s
            """;

    private final JdbcClient jdbc;

    @Override
    public List<AutoPauseRow> watched(Instant now) {
        return jdbc.sql(SELECT + " where s.auto_pause_late is not null or s.auto_paused_at is not null")
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public Optional<AutoPauseRow> of(String merchantId, Instant now) {
        return jdbc.sql(SELECT + " where s.merchant_id = :m")
                .param("m", merchantId)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public boolean markPaused(String merchantId, Instant at) {
        return jdbc.sql("""
                        update food.kitchen_settings set auto_paused_at = :at
                         where merchant_id = :m and auto_paused_at is null
                        """)
                        .param("m", merchantId)
                        .param("at", JdbcTimes.ts(at))
                        .update()
                == 1;
    }

    @Override
    public boolean markResumed(String merchantId) {
        return jdbc.sql("""
                        update food.kitchen_settings set auto_paused_at = null
                         where merchant_id = :m and auto_paused_at is not null
                        """)
                        .param("m", merchantId)
                        .update()
                == 1;
    }

    private static AutoPauseRow row(ResultSet rs) throws SQLException {
        return new AutoPauseRow(
                rs.getString("merchant_id"),
                KitchenSql.intOrNull(rs, "auto_pause_late"),
                JdbcTimes.instant(rs, "auto_paused_at"),
                rs.getInt("late"));
    }
}
