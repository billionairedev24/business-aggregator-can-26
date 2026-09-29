package ca.northline.food.application;

import ca.northline.food.application.ComboStore.ComboRow;
import ca.northline.food.application.ComboStore.Slot;
import ca.northline.food.application.ComboStore.Window;
import ca.northline.food.application.ComboUseCases.ComboCommand;
import ca.northline.food.application.ComboUseCases.ComboView;
import ca.northline.food.application.ComboUseCases.EditCombos;
import ca.northline.food.application.ComboUseCases.KitchenPromos;
import ca.northline.food.application.ComboUseCases.ListCombos;
import ca.northline.food.application.ComboUseCases.PromoView;
import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.domain.ComboPrice;
import ca.northline.food.domain.ComboPricing;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.KitchenPromo;
import ca.northline.food.domain.OpeningRanges;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class ComboService implements ListCombos, EditCombos, KitchenPromos {

    private final ComboStore combos;
    private final MenuStore menus;
    private final KitchenSettingsStore settings;

    @Override
    public List<ComboView> combos(String merchantId) {
        var items = menus.items(merchantId, null);
        return combos.combos(merchantId).stream()
                .map(c -> view(c, reference(c.slots(), items)))
                .toList();
    }

    @Override
    @Transactional
    public ComboView create(String merchantId, ComboCommand command) {
        var row = build(merchantId, Ids.next(), command);
        combos.insert(row.combo());
        return view(row.combo(), row.referenceCents());
    }

    @Override
    @Transactional
    public ComboView update(String merchantId, String comboId, ComboCommand command) {
        require(merchantId, comboId);
        var row = build(merchantId, comboId, command);
        combos.update(row.combo());
        return view(row.combo(), row.referenceCents());
    }

    @Override
    @Transactional
    public void delete(String merchantId, String comboId) {
        require(merchantId, comboId);
        combos.delete(merchantId, comboId);
    }

    @Override
    public List<PromoView> promos(String merchantId) {
        var saved = settings.promos(merchantId);
        return Arrays.stream(KitchenPromo.values())
                .map(p -> new PromoView(p, saved.getOrDefault(p, false)))
                .toList();
    }

    @Override
    @Transactional
    public PromoView set(String merchantId, KitchenPromo promo, boolean enabled, String actorId) {
        settings.savePromo(merchantId, promo, enabled, actorId);
        return new PromoView(promo, enabled);
    }

    private record Built(ComboRow combo, long referenceCents) {}

    private Built build(String merchantId, String id, ComboCommand c) {
        var items = menus.items(merchantId, null);
        var sectionIds = menus.sections(merchantId, null).stream()
                .map(MenuStore.SectionRow::id)
                .collect(Collectors.toSet());
        var itemIds = items.stream().map(ItemRow::id).collect(Collectors.toSet());
        var errors = new ArrayList<Violation>();
        for (int i = 0; i < c.slots().size(); i++) {
            var slot = c.slots().get(i);
            var known = slot.sectionId() != null
                    ? sectionIds.contains(slot.sectionId())
                    : !slot.itemIds().isEmpty() && itemIds.containsAll(slot.itemIds());
            if (!known || cheapest(slot, items) == null) {
                errors.add(new Violation("slots[" + i + "].items", "exists", KitchenMessages.SLOT_ITEMS));
            }
        }
        if (c.pricing() == ComboPricing.FIXED && (c.priceCents() == null || c.priceCents() <= 0)) {
            errors.add(new Violation("priceCents", "required", KitchenMessages.PRICE));
        }
        if (c.pricing() == ComboPricing.PERCENT_OFF
                && (c.discountPct() == null || c.discountPct() < 1 || c.discountPct() > 90)) {
            errors.add(new Violation("discountPct", "range", KitchenMessages.DISCOUNT));
        }
        var schedule = c.schedule();
        if (schedule != null) {
            if (schedule.days().isEmpty() || schedule.days().stream().anyMatch(d -> d < 1 || d > 7)) {
                errors.add(new Violation("schedule.days", "required", KitchenMessages.DAYS));
            }
            OpeningRanges.check("schedule.hours", List.of(List.of(schedule.from(), schedule.to())), errors);
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        var reference = reference(c.slots(), items);
        var discountBps = c.pricing() == ComboPricing.PERCENT_OFF ? Objects.requireNonNull(c.discountPct()) * 100 : 0;
        var fixed = c.pricing() == ComboPricing.FIXED ? Objects.requireNonNull(c.priceCents()) : 0L;
        ComboPrice.of(reference, c.pricing(), fixed, discountBps)
                .requireSaving(c.pricing() == ComboPricing.FIXED ? "priceCents" : "discountPct");
        var days = schedule == null
                ? List.<Integer>of()
                : schedule.days().stream().distinct().sorted().toList();
        var row = ComboRow.builder()
                .id(id)
                .merchantId(merchantId)
                .name(c.name().strip())
                .slots(c.slots().stream()
                        .map(s -> new Slot(
                                s.label().strip(),
                                s.qty(),
                                s.sectionId(),
                                s.sectionId() != null ? List.of() : List.copyOf(Set.copyOf(s.itemIds()))))
                        .toList())
                .pricing(c.pricing())
                .priceCents(c.pricing() == ComboPricing.FIXED ? fixed : null)
                .discountBps(c.pricing() == ComboPricing.PERCENT_OFF ? discountBps : null)
                .schedule(schedule == null ? null : new Window(days, schedule.from(), schedule.to()))
                .status(c.status())
                .swapsAllowed(c.swapsAllowed())
                .build();
        return new Built(row, reference);
    }

    private static ComboView view(ComboRow c, long reference) {
        var price = ComboPrice.of(
                reference,
                c.pricing(),
                Objects.requireNonNullElse(c.priceCents(), 0L),
                Objects.requireNonNullElse(c.discountBps(), 0));
        return new ComboView(
                c.id(),
                c.name(),
                c.slots().stream().map(Slot::label).collect(Collectors.joining(" + ")),
                c.slots(),
                c.pricing(),
                price.priceCents(),
                c.discountBps() == null ? null : c.discountBps() / 100,
                reference,
                price.savingCents(),
                c.schedule(),
                c.status(),
                c.swapsAllowed());
    }

    /** Σ qty × cheapest combo-eligible item of the slot. */
    private static long reference(List<Slot> slots, List<ItemRow> items) {
        return slots.stream()
                .mapToLong(s -> {
                    var cheapest = cheapest(s, items);
                    return cheapest == null ? 0 : cheapest * s.qty();
                })
                .sum();
    }

    private static @Nullable Long cheapest(Slot slot, List<ItemRow> items) {
        return items.stream()
                .filter(ItemRow::comboEligible)
                .filter(i -> slot.sectionId() != null
                        ? slot.sectionId().equals(i.sectionId())
                        : slot.itemIds().contains(i.id()))
                .map(ItemRow::priceCents)
                .min(Long::compare)
                .orElse(null);
    }

    private ComboRow require(String merchantId, String comboId) {
        return combos.combo(merchantId, comboId).orElseThrow(() -> new NotFound("combo", comboId));
    }
}
