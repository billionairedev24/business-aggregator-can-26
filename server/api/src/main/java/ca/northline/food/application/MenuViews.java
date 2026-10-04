package ca.northline.food.application;

import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemVisibility;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.MenuStatus;
import ca.northline.shared.Bytes;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;

/**
 * Read models of the Menu builder. Serialized as-is by the web layer (purpose-built for these screens, like the
 * studio dashboard). {@code GET …/menus} → {@link MenuSummary} is also the onboarding contract
 * ({@code [{id, name, sections:[{id, name}]}]}).
 */
public final class MenuViews {
    private MenuViews() {}

    /**
     * When a menu is offered. {@code mode}: {@code open_hours} (all open hours) · {@code window} ({@code days} ISO
     * weekdays, {@code from}–{@code to}) · {@code quote} (catering: "Quote via Services", {@code noticeHours} ahead).
     */
    public record MenuSchedule(
            String mode,
            List<Integer> days,
            @Nullable String from,
            @Nullable String to,
            @Nullable Integer noticeHours) {

        public static final MenuSchedule OPEN_HOURS = new MenuSchedule("open_hours", List.of(), null, null, null);
    }

    public record SectionRef(String id, String name, int sort, int itemCount) {}

    public record MenuSummary(
            String id,
            String name,
            MenuStatus status,
            MenuSchedule schedule,
            int sort,
            @Nullable Instant publishedAt,
            List<SectionRef> sections) {}

    public record ModifierRef(String id, String name) {}

    /**
     * One dish. {@code allergens} null = not declared yet; empty = declared "none". {@code soldOut} = sold out today
     * (switched off, or the daily limit is reached). {@code visibility} says whether customers can see it and why not.
     */
    public record ItemView(
            String id,
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
            boolean soldOut,
            ItemWindow availability,
            boolean comboEligible,
            List<ModifierRef> modifierGroups,
            ItemStatus status,
            ItemVisibility visibility,
            boolean hasPhoto,
            @Nullable Instant updatedAt,
            @Nullable PriceFlag priceCheck,
            @Nullable String ageClass) {}

    /**
     * S-67: the price is more than 40 % off comparable dishes ({@code deviationPct} +52 = above, -45 = below).
     * {@code confirmed}: the owner kept this price, so it is live; otherwise the dish waits ({@code price_check}).
     */
    public record PriceFlag(long medianCents, int deviationPct, boolean confirmed) {}

    public record SectionDetail(String id, String name, int sort, List<ItemView> items) {}

    /** The builder's page: one menu with its sections and items, and whether the kitchen is approved yet. */
    public record MenuDetail(
            String id,
            String name,
            MenuStatus status,
            MenuSchedule schedule,
            @Nullable Instant publishedAt,
            boolean kitchenApproved,
            List<SectionDetail> sections) {}

    /** CSV import result. */
    public record ImportResult(int itemsCreated, int sectionsCreated) {}

    public record Photo(Bytes bytes, String contentType) {}
}
