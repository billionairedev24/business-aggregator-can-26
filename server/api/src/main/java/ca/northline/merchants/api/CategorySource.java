package ca.northline.merchants.api;

import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The category taxonomy as the merchants module needs it (onboarding's Business step, the compliance ledger).
 * Implemented by the catalogue module, which owns {@code catalogue.categories} (S-37). It is declared here, not on
 * catalogue.api, because catalogue depends on merchants (licence checks), so merchants calling catalogue would be a
 * module cycle.
 */
public interface CategorySource {

    /** Every category (groups and leaves) under the given roots ({@code service|shop|food}), in no particular order. */
    List<Category> byRoots(Collection<String> roots);

    /** The categories with these ids; unknown ids are absent. */
    List<Category> byIds(Collection<String> ids);

    /**
     * S-120: those of {@code ids} whose businesses are approved only after a passed kitchen visit
     * ({@code catalogue.categories.site_visit_required}, staff data).
     */
    default Set<String> requiringKitchenVisit(Collection<String> ids) {
        return Set.of();
    }

    /**
     * @param parentId null for a group
     * @param names {@code name_i18n}: {@code en} always, {@code fr} when translated
     * @param regulatedRegistry the licence registry the category needs (AMVIC, AGLC …), or null
     */
    record Category(
            String id,
            @Nullable String parentId,
            String root,
            Map<String, String> names,
            @Nullable String regulatedRegistry) {

        public Category {
            names = Map.copyOf(names);
        }
    }
}
