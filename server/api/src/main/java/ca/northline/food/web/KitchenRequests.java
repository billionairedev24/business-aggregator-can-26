package ca.northline.food.web;

import static ca.northline.food.domain.KitchenMessages.*;

import ca.northline.food.application.ComboStore;
import ca.northline.food.application.ComboUseCases.ComboCommand;
import ca.northline.food.application.KitchenUseCases.DayCommand;
import ca.northline.food.application.KitchenUseCases.FulfilmentCommand;
import ca.northline.food.application.KitchenUseCases.HolidayCommand;
import ca.northline.food.application.KitchenUseCases.PrepCommand;
import ca.northline.food.application.MenuUseCases.ItemCommand;
import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.application.ModifierUseCases.GroupCommand;
import ca.northline.food.application.ModifierUseCases.OptionCommand;
import ca.northline.food.domain.ComboPricing;
import ca.northline.food.domain.ComboStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.PickRule;
import ca.northline.shared.CodedEnum;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMax;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/** Request bodies of the kitchen endpoints (messages: {@code KitchenMessages}, docs/DECISIONS.md › Kitchen). */
final class KitchenRequests {
    private KitchenRequests() {}

    private static <T> List<T> orEmpty(@Nullable List<T> list) {
        return list == null ? List.of() : list;
    }

    record MenuNameRequest(
            @NotBlank(message = MENU_NAME) @Size(max = 60, message = AT_MOST_60)
            String name) {}

    record SectionNameRequest(
            @NotBlank(message = SECTION_NAME) @Size(max = 60, message = AT_MOST_60)
            String name) {}

    record SectionOrderRequest(@NotNull(message = OPTION) List<String> sectionIds) {}

    record ScheduleRequest(
            @NotNull(message = OPTION) @Pattern(regexp = "open_hours|window|quote", message = OPTION)
            String mode,

            @Nullable List<Integer> days,
            @Nullable String from,
            @Nullable String to,
            @Nullable Integer noticeHours) {

        MenuSchedule toSchedule() {
            return new MenuSchedule(mode, orEmpty(days), from, to, noticeHours);
        }
    }

    /**
     * {@code POST /menu-items} (onboarding contract: {@code menuId, sectionId, name, description, priceCents, prepAddMin,
     * allergens[], modifierGroupIds[]}) and {@code PUT /menu-items/{id}}; the editor adds {@code dietary, availability,
     * dailyLimit, comboEligible, publish}.
     */
    record MenuItemRequest(
            @NotBlank(message = MENU_AND_SECTION) String menuId,
            @NotBlank(message = MENU_AND_SECTION) String sectionId,

            @NotBlank(message = ITEM_NAME) @Size(max = 80, message = AT_MOST_80)
            String name,

            @Nullable @Size(max = 500, message = AT_MOST_500)
            String description,

            @NotNull(message = PRICE) @Positive(message = PRICE) @Max(value = 1_000_000, message = PRICE)
            Long priceCents,

            @Nullable Integer prepAddMin,
            @NotNull(message = ALLERGENS) List<String> allergens,
            @Nullable List<String> dietary,
            @Nullable List<String> modifierGroupIds,

            @Nullable @Pattern(regexp = "always|lunch|after_5|weekends", message = OPTION)
            String availability,

            @Nullable Integer dailyLimit,
            @Nullable Boolean comboEligible,
            @Nullable Boolean publish) {

        ItemCommand toCommand() {
            return new ItemCommand(
                    menuId,
                    sectionId,
                    name,
                    description,
                    priceCents,
                    prepAddMin == null ? 0 : prepAddMin,
                    allergens,
                    orEmpty(dietary),
                    orEmpty(modifierGroupIds),
                    availability == null ? ItemWindow.ALWAYS : CodedEnum.fromCode(ItemWindow.class, availability),
                    dailyLimit,
                    !Boolean.FALSE.equals(comboEligible),
                    !Boolean.FALSE.equals(publish));
        }
    }

    record SoldOutRequest(@NotNull(message = OPTION) Boolean soldOut) {}

    record OptionRequest(
            @Nullable String id,

            @NotBlank(message = OPTION_NAME) @Size(max = 40, message = AT_MOST_40)
            String name,

            @Nullable @Min(value = 0, message = PRICE_DELTA) @Max(value = 10_000, message = PRICE_DELTA)
            Long priceDeltaCents,

            @Nullable Boolean isDefault,
            @Nullable Boolean soldOut) {

        OptionCommand toCommand() {
            return new OptionCommand(
                    id,
                    name,
                    priceDeltaCents == null ? 0 : priceDeltaCents,
                    Boolean.TRUE.equals(isDefault),
                    Boolean.TRUE.equals(soldOut));
        }
    }

    record ModifierGroupRequest(
            @NotBlank(message = GROUP_NAME) @Size(max = 40, message = AT_MOST_40)
            String name,

            @NotNull(message = OPTION) @Pattern(regexp = "exactly|at_least|up_to", message = OPTION)
            String pickRule,

            @NotNull(message = PICK_COUNT) @Min(value = 1, message = PICK_COUNT) @Max(value = 20, message = PICK_COUNT)
            Integer pickCount,

            @Nullable Boolean required,
            @Nullable List<String> showForOptionIds,
            @NotEmpty(message = OPTIONS_MIN) List<@Valid OptionRequest> options) {

        GroupCommand toCommand() {
            return new GroupCommand(
                    name,
                    CodedEnum.fromCode(PickRule.class, pickRule),
                    pickCount,
                    Boolean.TRUE.equals(required),
                    orEmpty(showForOptionIds),
                    options.stream().map(OptionRequest::toCommand).toList());
        }
    }

    record SlotRequest(
            @NotBlank(message = SLOT_LABEL) @Size(max = 60, message = AT_MOST_60)
            String label,

            @NotNull(message = SLOT_QTY) @Min(value = 1, message = SLOT_QTY) @Max(value = 20, message = SLOT_QTY)
            Integer qty,

            @Nullable String sectionId,
            @Nullable List<String> itemIds) {

        ComboStore.Slot toSlot() {
            return new ComboStore.Slot(
                    label, qty, sectionId == null || sectionId.isBlank() ? null : sectionId, orEmpty(itemIds));
        }
    }

    record WindowRequest(
            @Nullable List<Integer> days,
            @Nullable String from,
            @Nullable String to) {
        ComboStore.Window toWindow() {
            return new ComboStore.Window(
                    orEmpty(days), Objects.requireNonNullElse(from, ""), Objects.requireNonNullElse(to, ""));
        }
    }

    record ComboRequest(
            @NotBlank(message = COMBO_NAME) @Size(max = 60, message = AT_MOST_60)
            String name,

            @NotEmpty(message = SLOTS_MIN) List<@Valid SlotRequest> slots,

            @NotNull(message = OPTION) @Pattern(regexp = "fixed|percent_off", message = OPTION)
            String pricing,

            @Nullable Long priceCents,
            @Nullable Integer discountPct,
            @Nullable WindowRequest schedule,

            @NotNull(message = OPTION) @Pattern(regexp = "draft|live|scheduled|paused", message = OPTION)
            String status,

            @Nullable Boolean swapsAllowed) {

        ComboCommand toCommand() {
            return new ComboCommand(
                    name,
                    slots.stream().map(SlotRequest::toSlot).toList(),
                    CodedEnum.fromCode(ComboPricing.class, pricing),
                    priceCents,
                    discountPct,
                    schedule == null ? null : schedule.toWindow(),
                    CodedEnum.fromCode(ComboStatus.class, status),
                    Boolean.TRUE.equals(swapsAllowed));
        }
    }

    record PromoRequest(@NotNull(message = OPTION) Boolean enabled) {}

    record PrepRequest(
            @NotNull(message = OPTION) Integer defaultPrepMin,
            @NotNull(message = OPTION) Integer maxOrdersPer15,
            @NotNull(message = OPTION) Long largeOrderCents,
            @Nullable Integer autoPauseLate) {

        PrepCommand toCommand() {
            return new PrepCommand(defaultPrepMin, maxOrdersPer15, largeOrderCents, autoPauseLate);
        }
    }

    record FulfilmentRequest(
            boolean courier,
            boolean pickup,
            boolean mealKits,
            boolean scheduled,

            @NotNull(message = SCHEDULED_DAYS)
            @Min(value = 1, message = SCHEDULED_DAYS)
            @Max(value = 14, message = SCHEDULED_DAYS)
            Integer scheduledDays,

            boolean groupOrders,

            @NotNull(message = GROUP_MAX) @Min(value = 2, message = GROUP_MAX) @Max(value = 50, message = GROUP_MAX)
            Integer groupMax,

            @NotNull(message = RADIUS)
            @DecimalMin(value = "1", message = RADIUS)
            @DecimalMax(value = "25", message = RADIUS)
            BigDecimal radiusKm,

            @Nullable List<@Size(max = 40, message = AT_MOST_40) String> areas) {

        FulfilmentCommand toCommand() {
            return new FulfilmentCommand(
                    courier,
                    pickup,
                    mealKits,
                    scheduled,
                    scheduledDays,
                    groupOrders,
                    groupMax,
                    radiusKm,
                    orEmpty(areas));
        }
    }

    record DayRequest(
            @NotNull(message = OPTION) Integer weekday,
            @NotNull(message = OPTION) List<List<String>> ranges,
            @Nullable @Size(max = 80, message = NOTE_LENGTH) String note) {

        DayCommand toCommand() {
            return new DayCommand(weekday, ranges, note);
        }
    }

    record HoursRequest(@NotNull(message = OPTION) List<@Valid DayRequest> days) {}

    record HolidayRequest(
            @NotNull(message = FUTURE_DATE) LocalDate day,
            @Nullable List<List<String>> ranges,
            @Nullable @Size(max = 80, message = NOTE_LENGTH) String note) {

        HolidayCommand toCommand() {
            return new HolidayCommand(day, orEmpty(ranges), note);
        }
    }
}
