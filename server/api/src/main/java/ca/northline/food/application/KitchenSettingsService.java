package ca.northline.food.application;

import ca.northline.food.application.KitchenSettingsStore.DayRow;
import ca.northline.food.application.KitchenSettingsStore.HolidayRow;
import ca.northline.food.application.KitchenSettingsStore.SettingsRow;
import ca.northline.food.application.KitchenUseCases.DayCommand;
import ca.northline.food.application.KitchenUseCases.DayView;
import ca.northline.food.application.KitchenUseCases.EditKitchenSetup;
import ca.northline.food.application.KitchenUseCases.FulfilmentCommand;
import ca.northline.food.application.KitchenUseCases.FulfilmentView;
import ca.northline.food.application.KitchenUseCases.HolidayCommand;
import ca.northline.food.application.KitchenUseCases.HolidayView;
import ca.northline.food.application.KitchenUseCases.MenuScheduleView;
import ca.northline.food.application.KitchenUseCases.PrepCommand;
import ca.northline.food.application.KitchenUseCases.PrepView;
import ca.northline.food.application.KitchenUseCases.SetupView;
import ca.northline.food.application.KitchenUseCases.ViewKitchenSetup;
import ca.northline.food.domain.FulfilmentOption;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.KitchenPause;
import ca.northline.food.domain.KitchenTime;
import ca.northline.food.domain.OpeningRanges;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class KitchenSettingsService implements ViewKitchenSetup, EditKitchenSetup {

    static final Set<Integer> PREP_OPTIONS = Set.of(20, 25, 30, 40);
    static final Set<Integer> THROTTLE_OPTIONS = Set.of(4, 6, 8, 12);
    /** Large-order threshold → extra minutes ("$120 → +15 min", "$200 → +20 min"). */
    static final Map<Long, Integer> LARGE_ORDER_OPTIONS = Map.of(12_000L, 15, 20_000L, 20);

    static final Set<Integer> AUTO_PAUSE_OPTIONS = Set.of(3, 5);

    private final KitchenSettingsStore store;
    private final MenuStore menus;
    private final KitchenMerchantFacts merchant;
    private final Clock clock;

    /** A kitchen that never saved its settings: the defaults the design shows for a new kitchen. */
    static SettingsRow defaults(String merchantId) {
        return SettingsRow.builder()
                .merchantId(merchantId)
                .defaultPrepMin(25)
                .maxOrdersPer15(6)
                .prepBumpMin(0)
                .largeOrderCents(12_000)
                .largeOrderAddMin(15)
                .autoPauseLate(3)
                .fulfilment(List.of(
                        FulfilmentOption.COURIER.code(),
                        FulfilmentOption.PICKUP.code(),
                        FulfilmentOption.SCHEDULED.code()))
                .radiusKm(BigDecimal.valueOf(6))
                .deliveryAreas(List.of())
                .groupOrders(true)
                .groupMax(12)
                .scheduledDays(7)
                .build();
    }

    static SettingsRow settingsOf(KitchenSettingsStore store, String merchantId) {
        return store.settings(merchantId).orElseGet(() -> defaults(merchantId));
    }

    @Override
    public SetupView setup(String merchantId) {
        var s = settingsOf(store, merchantId);
        var hours = store.hours(merchantId);
        var now = clock.instant();
        return new SetupView(
                new PrepView(
                        s.defaultPrepMin(),
                        s.prepBumpMin(),
                        s.defaultPrepMin() + s.prepBumpMin(),
                        s.maxOrdersPer15(),
                        s.largeOrderCents(),
                        s.largeOrderAddMin(),
                        s.autoPauseLate()),
                KitchenPause.paused(s.pausedUntil(), now) ? s.pausedUntil() : null,
                new FulfilmentView(
                        s.fulfilment().contains(FulfilmentOption.COURIER.code()),
                        s.fulfilment().contains(FulfilmentOption.PICKUP.code()),
                        Math.max(5, s.defaultPrepMin() - 10),
                        Math.max(10, s.defaultPrepMin() - 5),
                        s.fulfilment().contains(FulfilmentOption.MEAL_KITS.code()),
                        s.fulfilment().contains(FulfilmentOption.SCHEDULED.code()),
                        s.scheduledDays(),
                        s.groupOrders(),
                        s.groupMax(),
                        s.radiusKm(),
                        s.deliveryAreas()),
                IntStream.rangeClosed(1, 7)
                        .mapToObj(d -> {
                            var day = hours.get(d);
                            return day == null
                                    ? new DayView(d, List.of(), null)
                                    : new DayView(d, day.ranges(), day.note());
                        })
                        .toList(),
                store.holidays(merchantId, KitchenTime.today(clock)).stream()
                        .map(h -> new HolidayView(h.id(), h.day(), h.ranges(), h.note()))
                        .toList(),
                menus.menus(merchantId).stream()
                        .map(m -> new MenuScheduleView(m.id(), m.name(), m.status(), m.schedule()))
                        .toList(),
                merchant.foodSafety(merchantId));
    }

    @Override
    @Transactional
    public SetupView prep(String merchantId, PrepCommand c) {
        var errors = new ArrayList<Violation>();
        if (!PREP_OPTIONS.contains(c.defaultPrepMin())) {
            errors.add(new Violation("defaultPrepMin", "option", KitchenMessages.OPTION));
        }
        if (!THROTTLE_OPTIONS.contains(c.maxOrdersPer15())) {
            errors.add(new Violation("maxOrdersPer15", "option", KitchenMessages.OPTION));
        }
        if (!LARGE_ORDER_OPTIONS.containsKey(c.largeOrderCents())) {
            errors.add(new Violation("largeOrderCents", "option", KitchenMessages.OPTION));
        }
        if (c.autoPauseLate() != null && !AUTO_PAUSE_OPTIONS.contains(c.autoPauseLate())) {
            errors.add(new Violation("autoPauseLate", "option", KitchenMessages.OPTION));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        store.save(settingsOf(store, merchantId).toBuilder()
                .defaultPrepMin(c.defaultPrepMin())
                .maxOrdersPer15(c.maxOrdersPer15())
                .largeOrderCents(c.largeOrderCents())
                .largeOrderAddMin(LARGE_ORDER_OPTIONS.getOrDefault(c.largeOrderCents(), 15))
                .autoPauseLate(c.autoPauseLate())
                .build());
        return setup(merchantId);
    }

    @Override
    @Transactional
    public SetupView fulfilment(String merchantId, FulfilmentCommand c) {
        var errors = new ArrayList<Violation>();
        if (c.radiusKm().compareTo(BigDecimal.ONE) < 0 || c.radiusKm().compareTo(BigDecimal.valueOf(25)) > 0) {
            errors.add(new Violation("radiusKm", "range", KitchenMessages.RADIUS));
        }
        if (c.groupMax() < 2 || c.groupMax() > 50) {
            errors.add(new Violation("groupMax", "range", KitchenMessages.GROUP_MAX));
        }
        if (c.scheduledDays() < 1 || c.scheduledDays() > 14) {
            errors.add(new Violation("scheduledDays", "range", KitchenMessages.SCHEDULED_DAYS));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        var options = new ArrayList<String>();
        if (c.courier()) {
            options.add(FulfilmentOption.COURIER.code());
        }
        if (c.pickup()) {
            options.add(FulfilmentOption.PICKUP.code());
        }
        if (c.mealKits()) {
            options.add(FulfilmentOption.MEAL_KITS.code());
        }
        if (c.scheduled()) {
            options.add(FulfilmentOption.SCHEDULED.code());
        }
        store.save(settingsOf(store, merchantId).toBuilder()
                .fulfilment(options)
                .scheduledDays(c.scheduledDays())
                .groupOrders(c.groupOrders())
                .groupMax(c.groupMax())
                .radiusKm(c.radiusKm())
                .deliveryAreas(c.areas().stream()
                        .map(String::strip)
                        .filter(a -> !a.isEmpty())
                        .distinct()
                        .toList())
                .build());
        return setup(merchantId);
    }

    @Override
    @Transactional
    public SetupView hours(String merchantId, List<DayCommand> days) {
        var errors = new ArrayList<Violation>();
        var rows = new ArrayList<DayRow>();
        var seen = new java.util.HashSet<Integer>();
        for (int i = 0; i < days.size(); i++) {
            var d = days.get(i);
            if (d.weekday() < 1 || d.weekday() > 7 || !seen.add(d.weekday())) {
                errors.add(new Violation("days[" + i + "].weekday", "option", KitchenMessages.OPTION));
                continue;
            }
            var ranges = OpeningRanges.check("days[" + i + "].ranges", d.ranges(), errors);
            rows.add(new DayRow(d.weekday(), ranges.asText(), blankToNull(d.note())));
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        store.saveHours(merchantId, rows);
        return setup(merchantId);
    }

    @Override
    @Transactional
    public SetupView addHoliday(String merchantId, HolidayCommand h, String actorId) {
        var errors = new ArrayList<Violation>();
        if (h.day().isBefore(KitchenTime.today(clock))) {
            errors.add(new Violation("day", "future", KitchenMessages.FUTURE_DATE));
        }
        var ranges = OpeningRanges.check("ranges", h.ranges(), errors);
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        store.insertHoliday(
                new HolidayRow(Ids.next(), merchantId, h.day(), ranges.asText(), blankToNull(h.note())), actorId);
        return setup(merchantId);
    }

    @Override
    @Transactional
    public SetupView removeHoliday(String merchantId, String holidayId) {
        if (!store.deleteHoliday(merchantId, holidayId)) {
            throw new NotFound("holiday hours", holidayId);
        }
        return setup(merchantId);
    }

    private static @org.jspecify.annotations.Nullable String blankToNull(
            @org.jspecify.annotations.Nullable String text) {
        return text == null || text.isBlank() ? null : text.strip();
    }
}
