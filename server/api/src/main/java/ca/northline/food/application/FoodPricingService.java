package ca.northline.food.application;

import ca.northline.food.api.FoodMenuPricing;
import ca.northline.food.domain.ComboPrice;
import ca.northline.food.domain.FoodOrderMessages;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.PickCheck;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.region.api.Markets;
import ca.northline.shared.Conflict;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link FoodMenuPricing}: the kitchen's live menu is the price list. Dishes: base price + each chosen option's delta,
 * choices checked with {@link PickCheck}. Combos: the fixed price (or the percent off the chosen dishes), one orderable,
 * combo-eligible dish per slot unit. All violations of a request are reported together (422).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class FoodPricingService implements FoodMenuPricing {

    static final int MAX_QTY = 20;
    static final int MAX_LINES = 30;
    static final int MAX_NOTE = 140;

    private final OrderableMenu menus;
    private final java.time.Clock clock;
    private final PublicDirectory directory;
    private final Markets markets;

    @Override
    public Priced price(Request request) {
        if (request.items().isEmpty() && request.combos().isEmpty()) {
            throw RuleViolation.of("items", "required", FoodOrderMessages.ADD_SOMETHING);
        }
        if (request.items().size() + request.combos().size() > MAX_LINES) {
            throw RuleViolation.of("items", "length", FoodOrderMessages.TOO_MANY_LINES);
        }
        var menu = menus.load(request.merchantId());
        // the kitchen's hours, windows and "sold out today" are in the time zone of its market
        var zone = markets.zone(directory
                .byId(request.merchantId())
                .map(PublicDirectory.PublicBusiness::province)
                .orElse(null));
        var at = request.at().atZone(zone);
        var today = clock.instant().atZone(zone).toLocalDate();
        var violations = new ArrayList<Violation>();
        var lines = new ArrayList<Line>();
        var prepAdd = 0;
        for (int i = 0; i < request.items().size(); i++) {
            var line = request.items().get(i);
            var field = "items[" + i + "]";
            if (line.qty() < 1 || line.qty() > MAX_QTY) {
                violations.add(new Violation(field + ".qty", "range", FoodOrderMessages.QTY));
            }
            var note = line.note() == null || line.note().isBlank()
                    ? null
                    : line.note().strip();
            if (note != null && note.length() > MAX_NOTE) {
                violations.add(new Violation(field + ".note", "length", FoodOrderMessages.NOTE_LONG));
            }
            var item = menu.item(line.itemId()).orElseThrow(() -> unavailable("This dish"));
            if (!menu.orderable(item, at, today)) {
                throw unavailable(item.name());
            }
            var groups = menu.groupsOf(item);
            violations.addAll(
                    PickCheck.check(field + ".optionIds", OrderableMenu.Snapshot.pickGroups(groups), line.optionIds()));
            var choices = choices(groups, line.optionIds());
            var unit = item.priceCents()
                    + choices.stream().mapToLong(Choice::deltaCents).sum();
            prepAdd = Math.max(prepAdd, item.prepAddMin());
            lines.add(new Line(
                    item.id(), null, item.name(), Math.max(1, line.qty()), unit, choices, note, item.ageClass()));
        }
        for (int i = 0; i < request.combos().size(); i++) {
            var line = request.combos().get(i);
            var field = "combos[" + i + "]";
            if (line.qty() < 1 || line.qty() > MAX_QTY) {
                violations.add(new Violation(field + ".qty", "range", FoodOrderMessages.QTY));
            }
            var combo = menu.combos().stream()
                    .filter(c -> c.id().equals(line.comboId()))
                    .findFirst()
                    .filter(c -> OrderableMenu.Snapshot.comboOpen(c, at))
                    .orElseThrow(() -> unavailable("This combo"));
            var units = combo.slots().stream().mapToInt(ComboStore.Slot::qty).sum();
            if (line.itemIds().size() != units) {
                violations.add(new Violation(field + ".itemIds", "required", FoodOrderMessages.COMBO_PICKS));
                continue;
            }
            var choices = new ArrayList<Choice>();
            @Nullable String comboAge = null;
            long separately = 0;
            var k = 0;
            var ok = true;
            for (var slot : combo.slots()) {
                var eligible = menu.eligible(slot);
                for (int u = 0; u < slot.qty(); u++, k++) {
                    var id = line.itemIds().get(k);
                    var dish = eligible.stream().filter(d -> d.id().equals(id)).findFirst();
                    if (dish.isEmpty()) {
                        ok = false;
                        continue;
                    }
                    if (!menu.orderable(dish.get(), at, today)) {
                        throw unavailable(dish.get().name());
                    }
                    separately += dish.get().priceCents();
                    if (comboAge == null) {
                        comboAge = dish.get().ageClass();
                    }
                    prepAdd = Math.max(prepAdd, dish.get().prepAddMin());
                    choices.add(new Choice(
                            null, slot.label(), dish.get().id(), dish.get().name(), 0));
                }
            }
            if (!ok) {
                violations.add(new Violation(field + ".itemIds", "combo", FoodOrderMessages.COMBO_ITEM));
                continue;
            }
            var price = ComboPrice.of(
                    separately,
                    combo.pricing(),
                    Objects.requireNonNullElse(combo.priceCents(), 0L),
                    Objects.requireNonNullElse(combo.discountBps(), 0));
            lines.add(new Line(
                    null,
                    combo.id(),
                    combo.name(),
                    Math.max(1, line.qty()),
                    price.priceCents(),
                    choices,
                    null,
                    comboAge));
        }
        if (!violations.isEmpty()) {
            throw new RuleViolation(violations);
        }
        return new Priced(lines, lines.stream().mapToLong(Line::totalCents).sum(), prepAdd);
    }

    private static List<Choice> choices(List<ModifierGroup> groups, List<String> optionIds) {
        var out = new ArrayList<Choice>();
        for (var g : groups) {
            for (var o : g.options()) {
                if (optionIds.contains(o.id())) {
                    out.add(new Choice(g.id(), g.name(), o.id(), o.name(), o.priceDeltaCents()));
                }
            }
        }
        return out;
    }

    private static Conflict unavailable(@Nullable String name) {
        return new Conflict(
                "item_unavailable", Objects.requireNonNullElse(name, "This dish") + " isn't available right now.");
    }
}
