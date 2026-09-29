package ca.northline.food.application;

import ca.northline.food.domain.ModifierGroup;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Outbound port: {@code food.modifier_groups} + {@code modifier_options} (and usage from {@code item_modifiers}). */
public interface ModifierGroupStore {

    List<ModifierGroup> groups(String merchantId);

    Optional<ModifierGroup> group(String merchantId, String groupId);

    /** Group id → number of items using it. */
    Map<String, Integer> usage(String merchantId);

    /** The subset of {@code groupIds} that belong to the merchant. */
    Set<String> owned(String merchantId, Collection<String> groupIds);

    void insert(ModifierGroup group);

    /** Updates the group and syncs its options (insert new, update kept, delete missing). */
    void update(ModifierGroup group);

    void delete(String merchantId, String groupId);

    int nextSort(String merchantId);
}
