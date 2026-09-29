package ca.northline.merchants.persistence;

import ca.northline.merchants.application.Taxonomy;
import ca.northline.merchants.domain.CategoryRoot;
import ca.northline.shared.CodedEnum;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * Reads {@code catalogue.categories} (owned by catalogue; logical cross-module read of reference data). The table has
 * no ordering or note column, so the order and the group notes come from db/seed/categories.json — the file the rows
 * were seeded from (ids are the seeder's stable slugs).
 */
@Repository
@RequiredArgsConstructor
class OnboardingTaxonomyQueries implements Taxonomy {

    private static final String SEED = "db/seed/categories.json";

    private final JdbcClient jdbc;
    private final MerchantJsonColumns json;
    private final SeedOrder seed = SeedOrder.load();

    private record Row(
            String id,
            @Nullable String parentId,
            CategoryRoot root,
            Map<String, String> names,
            @Nullable String regulator) {}

    @Override
    public List<Group> groups(Collection<CategoryRoot> roots) {
        if (roots.isEmpty()) {
            return List.of();
        }
        var rows = jdbc.sql("""
                        select id, parent_id, root, name_i18n::text as names, regulated_registry
                          from catalogue.categories where root in (:roots)
                        """)
                .param("roots", roots.stream().map(CategoryRoot::code).toList())
                .query((rs, _) -> new Row(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        CodedEnum.fromCode(CategoryRoot.class, rs.getString("root")),
                        names(rs.getString("names")),
                        rs.getString("regulated_registry")))
                .list();
        var items = new HashMap<String, List<Item>>();
        rows.stream()
                .filter(r -> r.parentId() != null)
                .sorted(Comparator.comparingInt(r -> seed.ordinal(r.id())))
                .forEach(r -> items.computeIfAbsent(Objects.requireNonNull(r.parentId()), _ -> new ArrayList<>())
                        .add(new Item(
                                r.id(), Objects.requireNonNull(r.parentId()), r.root(), r.names(), r.regulator())));
        return rows.stream()
                .filter(r -> r.parentId() == null && items.containsKey(r.id()))
                .sorted(Comparator.<Row>comparingInt(
                                r -> roots.stream().toList().indexOf(r.root()))
                        .thenComparingInt(r -> seed.ordinal(r.id())))
                .map(r -> new Group(
                        r.id(), r.root(), r.names(), seed.note(r.id()), items.getOrDefault(r.id(), List.of())))
                .toList();
    }

    @Override
    public Map<String, Item> leaves(Collection<String> ids) {
        if (ids.isEmpty()) {
            return Map.of();
        }
        var out = new LinkedHashMap<String, Item>();
        jdbc.sql("""
                        select id, parent_id, root, name_i18n::text as names, regulated_registry
                          from catalogue.categories where id in (:ids) and parent_id is not null
                        """)
                .param("ids", List.copyOf(ids))
                .query((rs, _) -> new Item(
                        rs.getString("id"),
                        rs.getString("parent_id"),
                        CodedEnum.fromCode(CategoryRoot.class, rs.getString("root")),
                        names(rs.getString("names")),
                        rs.getString("regulated_registry")))
                .list()
                .forEach(i -> out.put(i.id(), i));
        return out;
    }

    private Map<String, String> names(@Nullable String column) {
        var out = new LinkedHashMap<String, String>();
        json.map(column).forEach((k, v) -> out.put(k, String.valueOf(v)));
        return out;
    }

    /** Position and group note of every seeded id, from the seed file. */
    record SeedOrder(Map<String, Integer> ordinals, Map<String, String> notes) {
        int ordinal(String id) {
            return ordinals.getOrDefault(id, Integer.MAX_VALUE);
        }

        String note(String id) {
            return notes.getOrDefault(id, "");
        }

        static SeedOrder load() {
            var json = JsonMapper.builder().build();
            var ordinals = new HashMap<String, Integer>();
            var notes = new HashMap<String, String>();
            try (InputStream in =
                    OnboardingTaxonomyQueries.class.getClassLoader().getResourceAsStream(SEED)) {
                if (in == null) {
                    return new SeedOrder(ordinals, notes);
                }
                var root = json.readTree(in);
                int i = 0;
                for (var entry : root.properties()) {
                    if (entry.getKey().startsWith("$")) {
                        continue;
                    }
                    for (var group : entry.getValue()) {
                        var groupId =
                                entry.getKey() + "." + slug(group.get("group").asString());
                        ordinals.put(groupId, i++);
                        notes.put(groupId, group.path("note").asString(""));
                        for (var item : group.get("items")) {
                            ordinals.put(groupId + "." + slug(item.get(0).asString()), i++);
                        }
                    }
                }
            } catch (IOException ex) {
                throw new UncheckedIOException(ex);
            }
            return new SeedOrder(Map.copyOf(ordinals), Map.copyOf(notes));
        }

        /** Same slug rules as {@code ca.northline.tools.CategorySeeder}. */
        static String slug(String name) {
            return name.toLowerCase(Locale.ROOT)
                    .replace("&", "and")
                    .replace("é", "e")
                    .replaceAll("[^a-z0-9]+", "-")
                    .replaceAll("(^-|-$)", "");
        }
    }
}
