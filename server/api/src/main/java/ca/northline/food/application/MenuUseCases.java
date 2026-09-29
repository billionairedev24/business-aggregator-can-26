package ca.northline.food.application;

import ca.northline.food.application.MenuViews.ImportResult;
import ca.northline.food.application.MenuViews.ItemView;
import ca.northline.food.application.MenuViews.MenuDetail;
import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.application.MenuViews.MenuSummary;
import ca.northline.food.application.MenuViews.Photo;
import ca.northline.food.application.MenuViews.SectionRef;
import ca.northline.food.domain.ItemWindow;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/** Menu builder use cases: menus → sections → items. */
public final class MenuUseCases {
    private MenuUseCases() {}

    /** Menus with their sections (menu picker; onboarding contract). */
    public interface ListMenus {
        List<MenuSummary> menus(String merchantId);
    }

    /** One menu with sections and items (the builder page). */
    public interface ViewMenu {
        MenuDetail menu(String merchantId, String menuId);
    }

    /** "+ New menu", rename, schedule, publish / hide. */
    public interface EditMenus {
        MenuSummary create(String merchantId, String name);

        MenuSummary rename(String merchantId, String menuId, String name);

        MenuSummary schedule(String merchantId, String menuId, MenuSchedule schedule);

        /** Menu goes live (409 {@code not_approved} until Northline approves the kitchen). Publishes {@code menu.published}. */
        MenuSummary publish(String merchantId, String menuId, String actorId);

        MenuSummary hide(String merchantId, String menuId);
    }

    /** "Add section", rename, drag to reorder. */
    public interface EditSections {
        SectionRef add(String merchantId, String menuId, String name);

        SectionRef rename(String merchantId, String menuId, String sectionId, String name);

        List<SectionRef> reorder(String merchantId, String menuId, List<String> sectionIds);
    }

    /** Item editor ("Save &amp; publish" / "Save as draft"), "Sold out today", delete. */
    public interface EditMenuItems {
        ItemView create(String merchantId, ItemCommand command);

        ItemView update(String merchantId, String itemId, ItemCommand command);

        ItemView soldOut(String merchantId, String itemId, boolean soldOut);

        void delete(String merchantId, String itemId);
    }

    /** "Photo · required to go live". */
    public interface MenuItemPhotos {
        ItemView upload(String merchantId, String itemId, byte[] bytes, String contentType);

        Optional<Photo> photo(String merchantId, String itemId);
    }

    /** "Import from POS / CSV" — the CSV path; all rows or none. */
    public interface ImportMenuCsv {
        ImportResult importCsv(String merchantId, String menuId, byte[] csv);
    }

    /**
     * Item editor fields. {@code allergens} empty = declared "none". {@code publish}: "Save &amp; publish" (true) or
     * "Save as draft" (false); the onboarding contract omits it and gets publish (hidden until approved).
     */
    public record ItemCommand(
            String menuId,
            String sectionId,
            String name,
            @Nullable String description,
            long priceCents,
            int prepAddMin,
            List<String> allergens,
            List<String> dietary,
            List<String> modifierGroupIds,
            ItemWindow availability,
            @Nullable Integer dailyLimit,
            boolean comboEligible,
            boolean publish) {}
}
