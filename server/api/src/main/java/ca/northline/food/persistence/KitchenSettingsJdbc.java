package ca.northline.food.persistence;

import static ca.northline.food.persistence.KitchenSql.array;
import static ca.northline.food.persistence.KitchenSql.intOrNull;
import static ca.northline.food.persistence.KitchenSql.json;
import static ca.northline.food.persistence.KitchenSql.ranges;
import static ca.northline.food.persistence.KitchenSql.strings;

import ca.northline.food.application.KitchenSettingsStore;
import ca.northline.food.domain.KitchenPromo;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.time.LocalDate;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code food.kitchen_settings}, {@code opening_hours}, {@code holiday_hours}, {@code kitchen_promos}. */
@Repository
@RequiredArgsConstructor
class KitchenSettingsJdbc implements KitchenSettingsStore {

    private final JdbcClient jdbc;

    @Override
    public Optional<SettingsRow> settings(String merchantId) {
        return jdbc.sql("select * from food.kitchen_settings where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> SettingsRow.builder()
                        .merchantId(rs.getString("merchant_id"))
                        .defaultPrepMin(rs.getInt("default_prep_min"))
                        .maxOrdersPer15(rs.getInt("max_orders_per_15"))
                        .prepBumpMin(rs.getInt("prep_bump_min"))
                        .largeOrderCents(rs.getLong("large_order_cents"))
                        .largeOrderAddMin(rs.getInt("large_order_add_min"))
                        .autoPauseLate(intOrNull(rs, "auto_pause_late"))
                        .pausedUntil(JdbcTimes.instant(rs, "paused_until"))
                        .pausedBy(rs.getString("paused_by"))
                        .fulfilment(strings(rs, "fulfilment"))
                        .radiusKm(rs.getBigDecimal("radius_km"))
                        .deliveryAreas(strings(rs, "delivery_areas"))
                        .groupOrders(rs.getBoolean("group_orders"))
                        .groupMax(rs.getInt("group_max"))
                        .scheduledDays(rs.getInt("scheduled_days"))
                        .build())
                .optional();
    }

    @Override
    public void save(SettingsRow s) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("m", s.merchantId());
        p.put("prep", s.defaultPrepMin());
        p.put("max15", s.maxOrdersPer15());
        p.put("bump", s.prepBumpMin());
        p.put("largeCents", s.largeOrderCents());
        p.put("largeMin", s.largeOrderAddMin());
        p.put("autoPause", s.autoPauseLate());
        p.put("pausedUntil", JdbcTimes.ts(s.pausedUntil()));
        p.put("pausedBy", s.pausedBy());
        p.put("fulfilment", array(s.fulfilment()));
        p.put("radius", s.radiusKm());
        p.put("areas", array(s.deliveryAreas()));
        p.put("group", s.groupOrders());
        p.put("groupMax", s.groupMax());
        p.put("days", s.scheduledDays());
        jdbc.sql("""
                        insert into food.kitchen_settings (merchant_id, default_prep_min, max_orders_per_15, prep_bump_min,
                               large_order_cents, large_order_add_min, auto_pause_late, paused_until, paused_by, fulfilment,
                               radius_km, delivery_areas, group_orders, group_max, scheduled_days, updated_at)
                        values (:m, :prep, :max15, :bump, :largeCents, :largeMin, :autoPause, :pausedUntil, :pausedBy,
                               cast(:fulfilment as text[]), :radius, cast(:areas as text[]), :group, :groupMax, :days, now())
                        on conflict (merchant_id) do update set default_prep_min = excluded.default_prep_min,
                               max_orders_per_15 = excluded.max_orders_per_15, prep_bump_min = excluded.prep_bump_min,
                               large_order_cents = excluded.large_order_cents,
                               large_order_add_min = excluded.large_order_add_min,
                               auto_pause_late = excluded.auto_pause_late, paused_until = excluded.paused_until,
                               paused_by = excluded.paused_by, fulfilment = excluded.fulfilment,
                               radius_km = excluded.radius_km, delivery_areas = excluded.delivery_areas,
                               group_orders = excluded.group_orders, group_max = excluded.group_max,
                               scheduled_days = excluded.scheduled_days, updated_at = now()
                        """).params(p).update();
    }

    @Override
    public Map<Integer, DayRow> hours(String merchantId) {
        var out = new LinkedHashMap<Integer, DayRow>();
        jdbc.sql("select weekday, ranges, note from food.opening_hours where merchant_id = :m order by weekday")
                .param("m", merchantId)
                .query((rs, _) -> out.put(
                        rs.getInt("weekday"),
                        new DayRow(rs.getInt("weekday"), ranges(rs.getString("ranges")), rs.getString("note"))))
                .list();
        return out;
    }

    @Override
    public void saveHours(String merchantId, List<DayRow> days) {
        for (var d : days) {
            jdbc.sql("""
                            insert into food.opening_hours (merchant_id, weekday, ranges, note, updated_at)
                            values (:m, :day, cast(:ranges as jsonb), :note, now())
                            on conflict (merchant_id, weekday) do update set ranges = excluded.ranges, note = excluded.note,
                                   updated_at = now()
                            """)
                    .param("m", merchantId)
                    .param("day", d.weekday())
                    .param("ranges", json(d.ranges()))
                    .param("note", d.note(), java.sql.Types.VARCHAR)
                    .update();
        }
    }

    @Override
    public List<HolidayRow> holidays(String merchantId, LocalDate from) {
        return jdbc.sql("""
                        select id, merchant_id, day, ranges, note from food.holiday_hours
                         where merchant_id = :m and day >= :from order by day
                        """)
                .param("m", merchantId)
                .param("from", from)
                .query((rs, _) -> new HolidayRow(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getObject("day", LocalDate.class),
                        ranges(rs.getString("ranges")),
                        rs.getString("note")))
                .list();
    }

    @Override
    public void insertHoliday(HolidayRow h, String actorId) {
        jdbc.sql("""
                        insert into food.holiday_hours (id, merchant_id, day, ranges, note, created_by)
                        values (:id, :m, :day, cast(:ranges as jsonb), :note, :by)
                        """)
                .param("id", h.id())
                .param("m", h.merchantId())
                .param("day", h.day())
                .param("ranges", json(h.ranges()))
                .param("note", h.note(), java.sql.Types.VARCHAR)
                .param("by", actorId)
                .update();
    }

    @Override
    public boolean deleteHoliday(String merchantId, String holidayId) {
        return jdbc.sql("delete from food.holiday_hours where id = :id and merchant_id = :m")
                        .param("id", holidayId)
                        .param("m", merchantId)
                        .update()
                > 0;
    }

    @Override
    public Map<KitchenPromo, Boolean> promos(String merchantId) {
        var out = new EnumMap<KitchenPromo, Boolean>(KitchenPromo.class);
        jdbc.sql("select promo, enabled from food.kitchen_promos where merchant_id = :m")
                .param("m", merchantId)
                .query((rs, _) -> out.put(
                        CodedEnum.fromCode(KitchenPromo.class, rs.getString("promo")), rs.getBoolean("enabled")))
                .list();
        return out;
    }

    @Override
    public void savePromo(String merchantId, KitchenPromo promo, boolean enabled, String actorId) {
        jdbc.sql("""
                        insert into food.kitchen_promos (merchant_id, promo, enabled, updated_by, updated_at)
                        values (:m, :promo, :enabled, :by, now())
                        on conflict (merchant_id, promo) do update set enabled = excluded.enabled,
                               updated_by = excluded.updated_by, updated_at = now()
                        """)
                .param("m", merchantId)
                .param("promo", promo.code())
                .param("enabled", enabled)
                .param("by", actorId)
                .update();
    }
}
