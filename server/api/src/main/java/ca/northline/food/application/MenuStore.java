package ca.northline.food.application;

import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.MenuStatus;
import ca.northline.food.domain.PriceCheck;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import lombok.Builder;
import lombok.With;
import org.jspecify.annotations.Nullable;

/** Outbound port: {@code food.menus}, {@code menu_sections}, {@code menu_items} (+ {@code item_modifiers}). */
public interface MenuStore {

    List<MenuRow> menus(String merchantId);

    Optional<MenuRow> menu(String merchantId, String menuId);

    /** Sections of the merchant's menus (all menus when {@code menuId} is null), ordered by menu then sort. */
    List<SectionRow> sections(String merchantId, @Nullable String menuId);

    /** Items of the merchant (one menu when {@code menuId} is null → all), ordered by section sort then item sort. */
    List<ItemRow> items(String merchantId, @Nullable String menuId);

    Optional<ItemRow> item(String merchantId, String itemId);

    /** Any business's item (S-92: staff decisions outside a member request). */
    Optional<ItemRow> itemById(String itemId);

    void insertMenu(MenuRow menu);

    void updateMenu(MenuRow menu);

    void insertSection(SectionRow section);

    void updateSection(SectionRow section);

    /** Rewrites {@code sort} of the menu's sections in the given order. */
    void reorderSections(String menuId, List<String> sectionIds);

    void insertItem(ItemRow item);

    /** Updates every column of the item and replaces its modifier groups. */
    void updateItem(ItemRow item);

    void deleteItem(String merchantId, String itemId);

    /** Published items of the merchant (for the re-audit when the kitchen is approved). */
    List<ItemRow> publishedItems(String merchantId);

    /**
     * S-92: published dishes held by the price check (outside ±{@code bandPct} % of their median, price not confirmed)
     * of the businesses in scope, longest held first.
     */
    List<ItemRow> heldForPrice(ca.northline.shared.MerchantScope scope, int bandPct, int limit);

    @Builder(toBuilder = true)
    record MenuRow(
            String id,
            String merchantId,
            String name,
            MenuStatus status,
            MenuSchedule schedule,
            int sort,
            @Nullable Instant publishedAt) {}

    record SectionRow(String id, String menuId, String name, int sort) {}

    /** Age-restricted dishes: the platform took it off the menu for a missing licence ({@code licence_hold}, V344). */
    void licenceHold(String itemId, boolean held);

    boolean licenceHeld(String itemId);

    @With
    @Builder(toBuilder = true)
    record ItemRow(
            String id,
            String merchantId,
            String menuId,
            String sectionId,
            String name,
            @Nullable String description,
            long priceCents,
            @Nullable List<String> allergens,
            List<String> dietary,
            int prepAddMin,
            @Nullable Integer dailyLimit,
            int soldToday,
            @Nullable LocalDate soldOutOn,
            ItemWindow availability,
            boolean comboEligible,
            ItemStatus status,
            String vetting,
            boolean available,
            @Nullable String photoKey,
            @Nullable String photoContentType,
            int sort,
            List<String> modifierGroupIds,
            @Nullable Instant publishedAt,
            @Nullable Instant updatedAt,
            @Nullable Long priceMedianCents,
            @Nullable Long priceConfirmedCents,
            @Nullable String ageClass) {

        /** S-67: the price against comparable dishes. */
        public PriceCheck priceCheck() {
            return new PriceCheck(priceCents, priceMedianCents, priceConfirmedCents);
        }
    }
}
