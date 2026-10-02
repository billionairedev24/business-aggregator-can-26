package ca.northline.merchants.persistence;

import ca.northline.merchants.api.MerchantCategories.Business;
import ca.northline.merchants.api.MerchantCategories.Limit;
import ca.northline.merchants.api.MerchantCategories.Usage;
import ca.northline.merchants.application.MerchantCategoryStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.HashMap;
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

/** {@link MerchantCategoryStore} over {@code merchants.category_limits} and {@code merchants.merchant_categories}. */
@Repository
@RequiredArgsConstructor
class MerchantCategoryJdbc implements MerchantCategoryStore {

    private static final String LIMITS = """
            select l.merchant_type, l.max_categories, l.updated_at, l.updated_by,
                   (select count(*) from (
                      select c.merchant_id from merchants.merchant_categories c
                        join merchants.merchants m on m.id = c.merchant_id
                       where m.type = l.merchant_type and c.status <> 'rejected'
                       group by c.merchant_id having count(*) > l.max_categories) over_limit) as above
              from merchants.category_limits l""";

    private final JdbcClient jdbc;

    @Override
    public List<Limit> limits() {
        return jdbc.sql(LIMITS
                        + " order by array_position(array['provider','seller','both','kitchen'], l.merchant_type)")
                .query((rs, _) -> limit(rs))
                .list();
    }

    @Override
    public Optional<Limit> limit(String merchantType) {
        return jdbc.sql(LIMITS + " where l.merchant_type = :t")
                .param("t", merchantType)
                .query((rs, _) -> limit(rs))
                .optional();
    }

    @Override
    public void setLimit(String merchantType, int max, String actorId, Instant at) {
        jdbc.sql("""
                        insert into merchants.category_limits (merchant_type, max_categories, updated_by, updated_at)
                        values (:t, :max, :by, :at)
                        on conflict (merchant_type) do update
                           set max_categories = excluded.max_categories, updated_by = excluded.updated_by,
                               updated_at = excluded.updated_at""")
                .param("t", merchantType)
                .param("max", max)
                .param("by", actorId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    @Override
    public Map<String, Usage> usage() {
        var counts = new HashMap<String, Long>();
        var provinces = new HashMap<String, Set<String>>();
        jdbc.sql("""
                        select c.category_id, m.province, count(*) as n
                          from merchants.merchant_categories c
                          join merchants.merchants m on m.id = c.merchant_id
                         where m.status = 'active' and c.status in ('approved', 'requested')
                           and c.category_id not like 'suggested:%'
                         group by c.category_id, m.province""").query(rs -> {
            var id = Objects.requireNonNull(rs.getString("category_id"));
            counts.merge(id, rs.getLong("n"), Long::sum);
            var province = rs.getString("province");
            if (province != null) {
                provinces.computeIfAbsent(id, _ -> new HashSet<>()).add(province);
            }
        });
        var out = new HashMap<String, Usage>();
        counts.forEach((id, n) -> out.put(id, new Usage(n, provinces.getOrDefault(id, Set.of()))));
        return out;
    }

    @Override
    public Map<String, Map.Entry<String, List<Business>>> suggestions() {
        var out = new LinkedHashMap<String, Map.Entry<String, List<Business>>>();
        jdbc.sql("""
                        select c.category_id, c.suggested_name, m.id, m.display_name, m.legal_name, m.type, m.province,
                               m.status
                          from merchants.merchant_categories c
                          join merchants.merchants m on m.id = c.merchant_id
                         where c.category_id like 'suggested:%' and c.status = 'requested'
                         order by c.category_id, m.created_at""").query(rs -> {
            var id = Objects.requireNonNull(rs.getString("category_id"));
            var name = name(rs, id);
            var entry = out.computeIfAbsent(id, _ -> new AbstractMap.SimpleImmutableEntry<>(name, new ArrayList<>()));
            entry.getValue().add(business(rs));
        });
        return out;
    }

    @Override
    public List<String> holders(String suggestionId) {
        return jdbc.sql("""
                        select merchant_id from merchants.merchant_categories
                         where category_id = :s and status = 'requested' order by merchant_id""")
                .param("s", suggestionId)
                .query((rs, _) -> Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public List<String> holding(List<String> merchantIds, String categoryId) {
        if (merchantIds.isEmpty()) {
            return List.of();
        }
        return jdbc.sql("""
                        select merchant_id from merchants.merchant_categories
                         where category_id = :c and merchant_id in (:ids)""")
                .param("c", categoryId)
                .param("ids", merchantIds)
                .query((rs, _) -> Objects.requireNonNull(rs.getString(1)))
                .list();
    }

    @Override
    public void move(String merchantId, String suggestionId, String categoryId, String status) {
        jdbc.sql("""
                        update merchants.merchant_categories
                           set category_id = :c, status = :status, suggested_name = null
                         where merchant_id = :m and category_id = :s""")
                .param("c", categoryId)
                .param("status", status)
                .param("m", merchantId)
                .param("s", suggestionId)
                .update();
    }

    @Override
    public void drop(String merchantId, String suggestionId) {
        jdbc.sql("delete from merchants.merchant_categories where merchant_id = :m and category_id = :s")
                .param("m", merchantId)
                .param("s", suggestionId)
                .update();
    }

    private static String name(ResultSet rs, String id) throws SQLException {
        var name = rs.getString("suggested_name");
        return name == null || name.isBlank() ? id.substring("suggested:".length()) : name;
    }

    private static Business business(ResultSet rs) throws SQLException {
        var display = rs.getString("display_name");
        return new Business(
                Objects.requireNonNull(rs.getString("id")),
                display != null ? display : Objects.requireNonNullElse(rs.getString("legal_name"), ""),
                Objects.requireNonNull(rs.getString("type")),
                rs.getString("province"),
                rs.getString("status"));
    }

    private static Limit limit(ResultSet rs) throws SQLException {
        return new Limit(
                Objects.requireNonNull(rs.getString("merchant_type")),
                rs.getInt("max_categories"),
                rs.getLong("above"),
                JdbcTimes.requiredInstant(rs, "updated_at"),
                Objects.requireNonNull(rs.getString("updated_by")));
    }
}
