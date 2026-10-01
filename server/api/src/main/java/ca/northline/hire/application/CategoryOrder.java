package ca.northline.hire.application;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Order and group notes of the service taxonomy. {@code catalogue.categories} has neither, so they come from the file
 * the rows were seeded from ({@code db/seed/categories.json}, on the classpath; ids are CategorySeeder's slugs) — as
 * onboarding does.
 */
@Component
class CategoryOrder {

    static final String SEED = "db/seed/categories.json";

    private final Map<String, Integer> ordinals = new HashMap<>();
    private final Map<String, String> notes = new HashMap<>();

    CategoryOrder() {
        try (InputStream in = CategoryOrder.class.getClassLoader().getResourceAsStream(SEED)) {
            if (in == null) {
                return;
            }
            var root = JsonMapper.builder().build().readTree(in);
            int i = 0;
            for (var entry : root.properties()) {
                if (entry.getKey().startsWith("$")) {
                    continue;
                }
                for (var group : entry.getValue()) {
                    var groupId = entry.getKey() + "." + slug(group.get("group").asString());
                    ordinals.put(groupId, i++);
                    var note = group.path("note").asString("");
                    if (!note.isBlank()) {
                        notes.put(groupId, note);
                    }
                    for (var item : group.get("items")) {
                        ordinals.put(groupId + "." + slug(item.get(0).asString()), i++);
                    }
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    int ordinal(String id) {
        return ordinals.getOrDefault(id, Integer.MAX_VALUE);
    }

    Optional<String> note(String groupId) {
        return Optional.ofNullable(notes.get(groupId));
    }

    /** CategorySeeder's slug rule. */
    static String slug(String name) {
        return name.toLowerCase(java.util.Locale.ROOT)
                .replace("&", "and")
                .replace("é", "e")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }
}
