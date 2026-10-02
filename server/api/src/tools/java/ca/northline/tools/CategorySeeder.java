package ca.northline.tools;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import javax.sql.DataSource;
import org.jspecify.annotations.Nullable;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Upserts {@code db/seed/categories.json} into {@code catalogue.categories}: group rows first, then leaves with
 * {@code parent_id}. Idempotent (ids are stable slugs, {@code ON CONFLICT (id) DO UPDATE}).
 *
 * <p>Ids: {@code <root>.<group-slug>} for groups and {@code <root>.<group-slug>.<leaf-slug>} for leaves, e.g.
 * {@code service.automotive.mobile-mechanic}. {@code name_i18n} gets {@code en} only (no French in the seed yet). A row
 * staff edited in the console (S-94, {@code edited_at} set) is left as it is.
 * Also usable from tests: {@code new CategorySeeder(dataSource).seed()}.
 */
public final class CategorySeeder {

    public static final String RESOURCE = "db/seed/categories.json";

    private static final String UPSERT = """
            insert into catalogue.categories (id, parent_id, root, name_i18n, regulated_registry, requires_vs_check)
            values (?, ?, ?, ?::jsonb, ?, ?)
            on conflict (id) do update set parent_id = excluded.parent_id, root = excluded.root,
              name_i18n = excluded.name_i18n, regulated_registry = excluded.regulated_registry,
              requires_vs_check = excluded.requires_vs_check
              where catalogue.categories.edited_at is null
            """;

    record Category(
            String id,
            @Nullable String parentId,
            String root,
            String nameEn,
            @Nullable String regulatedRegistry,
            @Nullable Boolean requiresVsCheck) {}

    private final DataSource dataSource;
    private final JsonMapper json = JsonMapper.builder().build();

    public CategorySeeder(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    /** @return number of rows upserted */
    public int seed() {
        var categories = parse();
        try (Connection c = dataSource.getConnection();
                var ps = c.prepareStatement(UPSERT)) {
            c.setAutoCommit(false);
            for (var cat : categories) { // groups precede their leaves in parse() order
                ps.setString(1, cat.id());
                ps.setString(2, cat.parentId());
                ps.setString(3, cat.root());
                ps.setString(4, json.writeValueAsString(java.util.Map.of("en", cat.nameEn())));
                ps.setString(5, cat.regulatedRegistry());
                ps.setObject(6, cat.requiresVsCheck());
                ps.addBatch();
            }
            ps.executeBatch();
            c.commit();
            return categories.size();
        } catch (SQLException ex) {
            throw new IllegalStateException("Seeding catalogue.categories failed", ex);
        }
    }

    List<Category> parse() {
        JsonNode root = read();
        var groups = new ArrayList<Category>();
        var leaves = new ArrayList<Category>();
        for (var entry : root.properties()) {
            var rootName = entry.getKey();
            if (rootName.startsWith("$")) {
                continue;
            }
            for (JsonNode group : entry.getValue()) {
                var groupName = group.get("group").asString();
                var groupId = rootName + "." + slug(groupName);
                Boolean vsCheck = group.has("requires_vs_check")
                        ? group.get("requires_vs_check").asBoolean()
                        : null;
                groups.add(new Category(groupId, null, rootName, groupName, null, vsCheck));
                for (JsonNode item : group.get("items")) {
                    var name = item.get(0).asString();
                    var regulator = item.size() > 1 ? item.get(1).asString() : null;
                    leaves.add(new Category(groupId + "." + slug(name), groupId, rootName, name, regulator, vsCheck));
                }
            }
        }
        var all = new ArrayList<Category>(groups);
        all.addAll(leaves);
        var ids = new HashSet<String>();
        all.forEach(c -> {
            if (!ids.add(c.id())) {
                throw new IllegalStateException("Duplicate category id " + c.id());
            }
        });
        return List.copyOf(all);
    }

    private JsonNode read() {
        try (InputStream in = Objects.requireNonNull(
                CategorySeeder.class.getClassLoader().getResourceAsStream(RESOURCE), RESOURCE + " not on classpath")) {
            return json.readTree(in);
        } catch (IOException ex) {
            throw new UncheckedIOException(ex);
        }
    }

    static String slug(String name) {
        return name.toLowerCase(Locale.ROOT)
                .replace("&", "and")
                .replace("é", "e")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
