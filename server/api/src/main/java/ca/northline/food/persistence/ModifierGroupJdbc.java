package ca.northline.food.persistence;

import static ca.northline.food.persistence.KitchenSql.array;
import static ca.northline.food.persistence.KitchenSql.i18n;
import static ca.northline.food.persistence.KitchenSql.strings;

import ca.northline.food.application.ModifierGroupStore;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.ModifierRule;
import ca.northline.food.domain.PickRule;
import ca.northline.shared.CodedEnum;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code food.modifier_groups} + {@code modifier_options}; usage from {@code item_modifiers}. */
@Repository
@RequiredArgsConstructor
class ModifierGroupJdbc implements ModifierGroupStore {

    private final JdbcClient jdbc;

    private record GroupHead(
            String id, String merchantId, String name, ModifierRule rule, List<String> showFor, int sort) {}

    @Override
    public List<ModifierGroup> groups(String merchantId) {
        return load(merchantId, null);
    }

    @Override
    public Optional<ModifierGroup> group(String merchantId, String groupId) {
        return load(merchantId, groupId).stream().findFirst();
    }

    @Override
    public Map<String, Integer> usage(String merchantId) {
        var out = new LinkedHashMap<String, Integer>();
        jdbc.sql("""
                        select m.group_id, count(*) as n
                          from food.item_modifiers m join food.modifier_groups g on g.id = m.group_id
                         where g.merchant_id = :m group by m.group_id
                        """)
                .param("m", merchantId)
                .query((rs, _) -> out.put(rs.getString("group_id"), rs.getInt("n")))
                .list();
        return out;
    }

    @Override
    public Set<String> owned(String merchantId, Collection<String> groupIds) {
        if (groupIds.isEmpty()) {
            return Set.of();
        }
        return new HashSet<>(jdbc.sql("select id from food.modifier_groups where merchant_id = :m and id in (:ids)")
                .param("m", merchantId)
                .param("ids", groupIds)
                .query(String.class)
                .list());
    }

    @Override
    public void insert(ModifierGroup g) {
        jdbc.sql("""
                        insert into food.modifier_groups (id, merchant_id, name, name_i18n, pick_rule, pick_count, required,
                               min_select, max_select, show_for_option_ids, sort)
                        values (:id, :m, :name, cast(:i18n as jsonb), :rule, :count, :required, :min, :max,
                               cast(:showFor as text[]), :sort)
                        """).params(head(g)).update();
        syncOptions(g);
    }

    @Override
    public void update(ModifierGroup g) {
        jdbc.sql("""
                        update food.modifier_groups set name = :name, name_i18n = cast(:i18n as jsonb), pick_rule = :rule,
                               pick_count = :count, required = :required, min_select = :min, max_select = :max,
                               show_for_option_ids = cast(:showFor as text[]), sort = :sort
                         where id = :id and merchant_id = :m
                        """).params(head(g)).update();
        syncOptions(g);
    }

    @Override
    public void delete(String merchantId, String groupId) {
        var optionIds = jdbc.sql("select id from food.modifier_options where group_id = :g")
                .param("g", groupId)
                .query(String.class)
                .list();
        if (!optionIds.isEmpty()) {
            // nested groups that were shown only for these options lose that condition
            jdbc.sql("""
                            update food.modifier_groups
                               set show_for_option_ids = array(select unnest(show_for_option_ids)
                                                                except select unnest(cast(:ids as text[])))
                             where merchant_id = :m and show_for_option_ids && cast(:ids as text[])
                            """)
                    .param("m", merchantId)
                    .param("ids", optionIds.toArray(String[]::new))
                    .update();
        }
        jdbc.sql("delete from food.item_modifiers where group_id = :g")
                .param("g", groupId)
                .update();
        jdbc.sql("delete from food.modifier_options where group_id = :g")
                .param("g", groupId)
                .update();
        jdbc.sql("delete from food.modifier_groups where id = :g and merchant_id = :m")
                .param("g", groupId)
                .param("m", merchantId)
                .update();
    }

    @Override
    public int nextSort(String merchantId) {
        return jdbc.sql("select coalesce(max(sort) + 1, 0) from food.modifier_groups where merchant_id = :m")
                .param("m", merchantId)
                .query(Integer.class)
                .single();
    }

    private List<ModifierGroup> load(String merchantId, @org.jspecify.annotations.Nullable String groupId) {
        var heads = jdbc.sql("""
                        select id, merchant_id, coalesce(name, name_i18n ->> 'en', 'Options') as name, pick_rule, pick_count,
                               coalesce(required, false) as required, show_for_option_ids, sort
                          from food.modifier_groups
                         where merchant_id = :m and (cast(:g as text) is null or id = :g)
                         order by sort, id
                        """)
                .param("m", merchantId)
                .param("g", groupId, java.sql.Types.VARCHAR)
                .query((rs, _) -> new GroupHead(
                        rs.getString("id"),
                        rs.getString("merchant_id"),
                        rs.getString("name"),
                        new ModifierRule(
                                CodedEnum.fromCode(PickRule.class, rs.getString("pick_rule")),
                                rs.getInt("pick_count"),
                                rs.getBoolean("required")),
                        strings(rs, "show_for_option_ids"),
                        rs.getInt("sort")))
                .list();
        if (heads.isEmpty()) {
            return List.of();
        }
        var options = new LinkedHashMap<String, List<ModifierGroup.Option>>();
        jdbc.sql("""
                        select id, group_id, coalesce(name, name_i18n ->> 'en', 'Option') as name,
                               coalesce(price_delta_cents, 0) as delta, coalesce(is_default, false) as is_default,
                               coalesce(sold_out, false) as sold_out, sort
                          from food.modifier_options where group_id in (:ids) order by group_id, sort, id
                        """)
                .param("ids", heads.stream().map(GroupHead::id).toList())
                .query((rs, _) -> options.computeIfAbsent(rs.getString("group_id"), _ -> new ArrayList<>())
                        .add(new ModifierGroup.Option(
                                rs.getString("id"),
                                rs.getString("name"),
                                rs.getLong("delta"),
                                rs.getBoolean("is_default"),
                                rs.getBoolean("sold_out"),
                                rs.getInt("sort"))))
                .list();
        return heads.stream()
                .map(h -> new ModifierGroup(
                        h.id(),
                        h.merchantId(),
                        h.name(),
                        h.rule(),
                        h.showFor(),
                        options.getOrDefault(h.id(), List.of()),
                        h.sort()))
                .toList();
    }

    private void syncOptions(ModifierGroup g) {
        var keep = g.options().stream()
                .map(ModifierGroup.Option::id)
                .filter(Objects::nonNull)
                .toList();
        jdbc.sql("delete from food.modifier_options where group_id = :g and not (id = any(cast(:keep as text[])))")
                .param("g", g.id())
                .param("keep", array(keep))
                .update();
        for (var o : g.options()) {
            jdbc.sql("""
                            insert into food.modifier_options (id, group_id, name, name_i18n, price_delta_cents, is_default,
                                   sold_out, sort)
                            values (:id, :g, :name, cast(:i18n as jsonb), :delta, :isDefault, :soldOut, :sort)
                            on conflict (id) do update set name = excluded.name, name_i18n = excluded.name_i18n,
                                   price_delta_cents = excluded.price_delta_cents, is_default = excluded.is_default,
                                   sold_out = excluded.sold_out, sort = excluded.sort
                            """)
                    .param("id", Objects.requireNonNull(o.id()))
                    .param("g", g.id())
                    .param("name", o.name())
                    .param("i18n", i18n(o.name()))
                    .param("delta", o.priceDeltaCents())
                    .param("isDefault", o.isDefault())
                    .param("soldOut", o.soldOut())
                    .param("sort", o.sort())
                    .update();
        }
    }

    private static Map<String, @org.jspecify.annotations.Nullable Object> head(ModifierGroup g) {
        var p = new java.util.HashMap<String, @org.jspecify.annotations.Nullable Object>();
        p.put("id", g.id());
        p.put("m", g.merchantId());
        p.put("name", g.name());
        p.put("i18n", i18n(g.name()));
        p.put("rule", g.rule().rule().code());
        p.put("count", g.rule().count());
        p.put("required", g.rule().required());
        p.put("min", g.rule().minSelect());
        p.put("max", g.rule().maxSelect());
        p.put("showFor", array(g.showForOptionIds()));
        p.put("sort", g.sort());
        return p;
    }
}
