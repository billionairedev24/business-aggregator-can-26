package ca.northline.merchants.persistence;

import ca.northline.merchants.api.CategorySource;
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
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/**
 * The taxonomy from the catalogue module ({@link CategorySource}, S-37 — catalogue owns {@code catalogue.categories}).
 * The table has no ordering or note column, so the order and the group notes come from db/seed/categories.json — the
 * file the rows were seeded from (ids are the seeder's stable slugs).
 */
@Repository
@RequiredArgsConstructor
class OnboardingTaxonomyQueries implements Taxonomy {

    private static final String SEED = "db/seed/categories.json";

    private final CategorySource categories;
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
        var rows = categories.byRoots(roots.stream().map(CategoryRoot::code).toList()).stream()
                .map(c -> new Row(
                        c.id(),
                        c.parentId(),
                        CodedEnum.fromCode(CategoryRoot.class, c.root()),
                        c.names(),
                        c.regulatedRegistry()))
                .toList();
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
        categories.byIds(ids).stream()
                .filter(c -> c.parentId() != null)
                .forEach(c -> out.put(
                        c.id(),
                        new Item(
                                c.id(),
                                Objects.requireNonNull(c.parentId()),
                                CodedEnum.fromCode(CategoryRoot.class, c.root()),
                                c.names(),
                                c.regulatedRegistry())));
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
