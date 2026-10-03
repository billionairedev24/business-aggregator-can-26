package ca.northline.catalogue.persistence;

import ca.northline.merchants.api.CategorySource;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@code catalogue.categories} for the merchants module (S-37: it used to read the table itself). */
@Repository
@RequiredArgsConstructor
class CategorySourceAdapter implements CategorySource {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final String COLUMNS =
            "select id, parent_id, root, name_i18n::text as names, regulated_registry from catalogue.categories";

    private final JdbcClient jdbc;

    @Override
    public List<Category> byRoots(Collection<String> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + " where root in (:roots)")
                .param("roots", List.copyOf(roots))
                .query((rs, _) -> new Category(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        rs.getString("root"),
                        names(rs.getString("names")),
                        rs.getString("regulated_registry")))
                .list();
    }

    @Override
    public List<Category> byIds(Collection<String> ids) {
        if (ids.isEmpty()) {
            return List.of();
        }
        return jdbc.sql(COLUMNS + " where id in (:ids)")
                .param("ids", List.copyOf(ids))
                .query((rs, _) -> new Category(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        rs.getString("root"),
                        names(rs.getString("names")),
                        rs.getString("regulated_registry")))
                .list();
    }

    @Override
    public Set<String> requiringKitchenVisit(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Set.of();
        }
        return Set.copyOf(jdbc.sql("select id from catalogue.categories where id in (:ids) and site_visit_required")
                .param("ids", List.copyOf(ids))
                .query(String.class)
                .list());
    }

    private static Map<String, String> names(@Nullable String column) {
        var out = new LinkedHashMap<String, String>();
        if (column != null) {
            JSON.readTree(column)
                    .properties()
                    .forEach(e -> out.put(e.getKey(), e.getValue().asString()));
        }
        return out;
    }
}
