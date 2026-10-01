package ca.northline.food.persistence;

import static ca.northline.food.persistence.KitchenSql.intOrNull;
import static ca.northline.food.persistence.KitchenSql.ranges;
import static ca.northline.food.persistence.KitchenSql.strings;

import ca.northline.food.application.KitchenCalendarStore;
import ca.northline.shared.JdbcTimes;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@link KitchenCalendarStore}: four set-based reads for any number of kitchens. */
@Repository
@RequiredArgsConstructor
class KitchenCalendarJdbc implements KitchenCalendarStore {

    private final JdbcClient jdbc;

    @Override
    public List<CalendarRow> calendars(Collection<String> merchantIds, LocalDate from, Instant now) {
        var week = new HashMap<String, Map<Integer, List<List<String>>>>();
        jdbc.sql("select merchant_id, weekday, ranges from food.opening_hours where merchant_id in (:ids)")
                .param("ids", merchantIds)
                .query((rs, _) -> week.computeIfAbsent(rs.getString("merchant_id"), _ -> new HashMap<>())
                        .put(rs.getInt("weekday"), ranges(rs.getString("ranges"))))
                .list();
        var holidays = new HashMap<String, Map<LocalDate, List<List<String>>>>();
        jdbc.sql("""
                        select merchant_id, day, ranges from food.holiday_hours
                         where merchant_id in (:ids) and day between :from and :to
                        """)
                .param("ids", merchantIds)
                .param("from", from)
                .param("to", from.plusDays(8))
                .query((rs, _) -> holidays.computeIfAbsent(rs.getString("merchant_id"), _ -> new HashMap<>())
                        .put(rs.getObject("day", LocalDate.class), ranges(rs.getString("ranges"))))
                .list();
        return jdbc.sql("""
                        select s.merchant_id, s.default_prep_min, s.prep_bump_min, s.auto_pause_late, s.paused_until,
                               s.fulfilment,
                               (select count(*) from food.kitchen_tickets t
                                 where t.merchant_id = s.merchant_id and t.stage = 'cooking' and t.ready_by < :now) as late,
                               exists (select 1 from food.menus m
                                         join food.menu_sections sec on sec.menu_id = m.id
                                         join food.menu_items i on i.section_id = sec.id
                                        where m.merchant_id = s.merchant_id and m.status = 'live'
                                          and i.status = 'published' and i.vetting = 'approved') as menu_live,
                               s.radius_km, s.scheduled_days,
                               coalesce((select avg(i.price_cents)::bigint from food.menus m
                                          join food.menu_sections sec on sec.menu_id = m.id
                                          join food.menu_items i on i.section_id = sec.id
                                         where m.merchant_id = s.merchant_id and m.status = 'live'
                                           and i.status = 'published' and i.vetting = 'approved'), 0) as avg_item
                          from food.kitchen_settings s
                         where s.merchant_id in (:ids)
                        """)
                .param("ids", merchantIds)
                .param("now", JdbcTimes.ts(now))
                .query((rs, _) -> {
                    var id = rs.getString("merchant_id");
                    return new CalendarRow(
                            id,
                            rs.getInt("default_prep_min"),
                            rs.getInt("prep_bump_min"),
                            intOrNull(rs, "auto_pause_late"),
                            JdbcTimes.instant(rs, "paused_until"),
                            strings(rs, "fulfilment"),
                            week.getOrDefault(id, Map.of()),
                            holidays.getOrDefault(id, Map.of()),
                            rs.getInt("late"),
                            rs.getBoolean("menu_live"),
                            rs.getObject("radius_km") == null ? null : rs.getDouble("radius_km"),
                            rs.getLong("avg_item"),
                            rs.getInt("scheduled_days"));
                })
                .list();
    }
}
