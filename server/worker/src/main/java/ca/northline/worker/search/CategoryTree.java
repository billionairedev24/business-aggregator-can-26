package ca.northline.worker.search;

import ca.northline.searchindex.SearchLanguage;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;

/**
 * {@code catalogue.categories} (≈ 200 rows, upserted by {@code seedCategories}) held in memory for five minutes, and
 * re-read at once when an unknown id is asked for: the path from the root group to a leaf and the names in each
 * language (French falls back to English).
 */
final class CategoryTree {

    static final Duration TTL = Duration.ofMinutes(5);

    record Category(
            String id, @Nullable String parentId, @Nullable String root, String nameEn, String nameFr) {}

    private final JdbcClient jdbc;
    private final Clock clock;
    private volatile Map<String, Category> byId = Map.of();
    private volatile Instant loadedAt = Instant.EPOCH;

    CategoryTree(JdbcClient jdbc, Clock clock) {
        this.jdbc = jdbc;
        this.clock = clock;
    }

    /** Ids from the root to {@code id} (empty for an unknown id). */
    List<String> path(@Nullable String id) {
        var categories = categories(id);
        var path = new ArrayList<String>();
        var current = id == null ? null : categories.get(id);
        while (current != null && path.size() < 10 && !path.contains(current.id())) {
            path.add(current.id());
            current = current.parentId() == null ? null : categories.get(current.parentId());
        }
        Collections.reverse(path);
        return List.copyOf(path);
    }

    @Nullable
    String root(@Nullable String id) {
        var category = id == null ? null : categories(id).get(id);
        return category == null ? null : category.root();
    }

    String name(String id, SearchLanguage language) {
        var category = categories(id).get(id);
        if (category == null) {
            return id;
        }
        return language == SearchLanguage.FR ? category.nameFr() : category.nameEn();
    }

    /** Names of the categories along the paths of {@code ids}, leaf names last, no duplicates. */
    List<String> names(List<String> ids, SearchLanguage language) {
        return ids.stream()
                .flatMap(id -> path(id).stream())
                .distinct()
                .map(id -> name(id, language))
                .toList();
    }

    private Map<String, Category> categories(@Nullable String wanted) {
        var now = clock.instant();
        var map = byId;
        if (now.isAfter(loadedAt.plus(TTL)) || map.isEmpty() || (wanted != null && !map.containsKey(wanted))) {
            var loaded = new HashMap<String, Category>();
            jdbc.sql("""
                            select id, parent_id, root,
                                   coalesce(name_i18n->>'en', id) as name_en,
                                   coalesce(nullif(name_i18n->>'fr', ''), name_i18n->>'en', id) as name_fr
                              from catalogue.categories""")
                    .query((rs, _) -> new Category(
                            rs.getString("id"),
                            rs.getString("parent_id"),
                            rs.getString("root"),
                            Objects.requireNonNull(rs.getString("name_en")),
                            Objects.requireNonNull(rs.getString("name_fr"))))
                    .list()
                    .forEach(c -> loaded.put(c.id(), c));
            map = Map.copyOf(loaded);
            byId = map;
            loadedAt = now;
        }
        return map;
    }
}
