package ca.northline.food.application;

import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.PriceCheck;
import ca.northline.food.domain.MenuStatus;
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
            @Nullable Long priceConfirmedCents) {

        /** S-67: the price against comparable dishes. */
        public PriceCheck priceCheck() {
            return new PriceCheck(priceCents, priceMedianCents, priceConfirmedCents);
        }
    }
}
