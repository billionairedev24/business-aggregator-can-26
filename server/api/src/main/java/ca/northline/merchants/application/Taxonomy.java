package ca.northline.merchants.application;

import ca.northline.merchants.domain.CategoryRoot;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: the category taxonomy ({@code catalogue.categories}, seeded from db/seed/categories.json). Read-only;
 * the catalogue module owns the table. Groups and leaves come back in the seed file's order.
 */
public interface Taxonomy {

    /** A leaf category. {@code names} holds {@code name_i18n} (en always, fr when translated). */
    record Item(
            String id,
            String groupId,
            CategoryRoot root,
            Map<String, String> names,
            @Nullable String regulator) {
        public Item {
            names = Map.copyOf(names);
        }

        public String name(String lang) {
            return names.getOrDefault(lang, names.getOrDefault("en", id));
        }
    }

    /** A group; {@code note} is the regulator hint shown next to its name ("AMVIC licence checked"). */
    record Group(String id, CategoryRoot root, Map<String, String> names, String note, List<Item> items) {
        public Group {
            names = Map.copyOf(names);
            items = List.copyOf(items);
        }

        public String name(String lang) {
            return names.getOrDefault(lang, names.getOrDefault("en", id));
        }
    }

    /** Groups with their leaves, in seed order. */
    List<Group> groups(Collection<CategoryRoot> roots);

    /** Leaves by id; unknown ids are absent. */
    Map<String, Item> leaves(Collection<String> ids);
}
