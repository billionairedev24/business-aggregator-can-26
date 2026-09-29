package ca.northline.food.persistence;

import static ca.northline.food.persistence.KitchenSql.i18n;
import static ca.northline.food.persistence.KitchenSql.intOrNull;
import static ca.northline.food.persistence.KitchenSql.json;
import static ca.northline.food.persistence.KitchenSql.longOrNull;

import ca.northline.food.application.ComboStore;
import ca.northline.food.domain.ComboPricing;
import ca.northline.food.domain.ComboStatus;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code food.combos}: {@code rules = {"slots":[…]}}, {@code schedule = {days, from, to}} or null. */
@Repository
@RequiredArgsConstructor
class ComboJdbc implements ComboStore {

    private record Rules(List<Slot> slots) {}

    private final JdbcClient jdbc;

    @Override
    public List<ComboRow> combos(String merchantId) {
        return jdbc.sql("select * from food.combos where merchant_id = :m order by created_at, id")
                .param("m", merchantId)
                .query((rs, _) -> row(rs))
                .list();
    }

    @Override
    public Optional<ComboRow> combo(String merchantId, String comboId) {
        return jdbc.sql("select * from food.combos where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", comboId)
                .query((rs, _) -> row(rs))
                .optional();
    }

    @Override
    public void insert(ComboRow c) {
        jdbc.sql("""
                        insert into food.combos (id, merchant_id, name, name_i18n, rules, price_cents, discount_bps, schedule,
                               status, pricing, swaps_allowed)
                        values (:id, :m, :name, cast(:i18n as jsonb), cast(:rules as jsonb), :price, :bps,
                               cast(:schedule as jsonb), :status, :pricing, :swaps)
                        """).params(params(c)).update();
    }

    @Override
    public void update(ComboRow c) {
        jdbc.sql("""
                        update food.combos set name = :name, name_i18n = cast(:i18n as jsonb), rules = cast(:rules as jsonb),
                               price_cents = :price, discount_bps = :bps, schedule = cast(:schedule as jsonb),
                               status = :status, pricing = :pricing, swaps_allowed = :swaps, updated_at = now()
                         where id = :id and merchant_id = :m
                        """).params(params(c)).update();
    }

    @Override
    public void delete(String merchantId, String comboId) {
        jdbc.sql("delete from food.combos where id = :id and merchant_id = :m")
                .param("id", comboId)
                .param("m", merchantId)
                .update();
    }

    private static Map<String, @Nullable Object> params(ComboRow c) {
        var p = new HashMap<String, @Nullable Object>();
        p.put("id", c.id());
        p.put("m", c.merchantId());
        p.put("name", c.name());
        p.put("i18n", i18n(c.name()));
        p.put("rules", json(new Rules(c.slots())));
        p.put("price", c.priceCents());
        p.put("bps", c.discountBps());
        p.put("schedule", c.schedule() == null ? null : json(c.schedule()));
        p.put("status", c.status().code());
        p.put("pricing", c.pricing().code());
        p.put("swaps", c.swapsAllowed());
        return p;
    }

    private static ComboRow row(ResultSet rs) throws SQLException {
        var rules = rs.getString("rules");
        var schedule = rs.getString("schedule");
        return ComboRow.builder()
                .id(rs.getString("id"))
                .merchantId(rs.getString("merchant_id"))
                .name(Objects.requireNonNullElse(rs.getString("name"), "Combo"))
                .slots(
                        rules == null
                                ? List.of()
                                : KitchenSql.read(rules, Rules.class).slots())
                .pricing(CodedEnum.fromCode(ComboPricing.class, rs.getString("pricing")))
                .priceCents(longOrNull(rs, "price_cents"))
                .discountBps(intOrNull(rs, "discount_bps"))
                .schedule(schedule == null || schedule.equals("null") ? null : KitchenSql.read(schedule, Window.class))
                .status(CodedEnum.fromCode(
                        ComboStatus.class, Objects.requireNonNullElse(rs.getString("status"), "draft")))
                .swapsAllowed(rs.getBoolean("swaps_allowed"))
                .build();
    }
}
