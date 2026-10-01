package ca.northline.food.application;

import ca.northline.food.application.ComboStore.ComboRow;
import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.application.MenuStore.MenuRow;
import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.domain.ComboStatus;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.MenuStatus;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.OrderingWindow;
import ca.northline.food.domain.PickCheck;
import java.time.LocalDate;
import java.time.ZonedDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * The part of a kitchen's menus a customer can see (S-57): live menus, their sections, the dishes that are published
 * and approved, the modifier groups on them, and combos that are live or scheduled. Loaded once per request through the
 * Studio's own stores.
 */
@Component
@RequiredArgsConstructor
class OrderableMenu {

    private final MenuStore menus;
    private final ModifierGroupStore groups;
    private final ComboStore combos;

    record Snapshot(
            Map<String, MenuRow> menus,
            List<SectionRow> sections,
            Map<String, ItemRow> items,
            Map<String, ModifierGroup> groups,
            List<ComboRow> combos) {

        List<ItemRow> itemsOf(String sectionId) {
            return items.values().stream()
                    .filter(i -> i.sectionId().equals(sectionId))
                    .toList();
        }

        Optional<ItemRow> item(String id) {
            return Optional.ofNullable(items.get(id));
        }

        /** Sold out today: switched off, "Sold out today", or its daily limit reached. */
        static boolean soldOut(ItemRow i, LocalDate today) {
            return !i.available()
                    || (i.soldOutOn() != null && !i.soldOutOn().isBefore(today))
                    || (i.dailyLimit() != null && i.soldToday() >= i.dailyLimit());
        }

        /**
         * Orderable for a kitchen cooking at {@code at} (in the time zone of its market): not sold out, inside its own
         * window and its menu's.
         */
        boolean orderable(ItemRow i, ZonedDateTime at, LocalDate today) {
            var local = at.toLocalDateTime();
            var menu = menus.get(i.menuId());
            return menu != null
                    && !soldOut(i, today)
                    && OrderingWindow.item(i.availability(), local)
                    && OrderingWindow.menu(
                            menu.schedule().mode(),
                            menu.schedule().days(),
                            menu.schedule().from(),
                            menu.schedule().to(),
                            local);
        }

        /** A live combo, or a scheduled one inside its window. */
        static boolean comboOpen(ComboRow c, ZonedDateTime at) {
            if (c.status() == ComboStatus.LIVE) {
                return c.schedule() == null || window(c, at);
            }
            return c.status() == ComboStatus.SCHEDULED && c.schedule() != null && window(c, at);
        }

        private static boolean window(ComboRow c, ZonedDateTime at) {
            var w = c.schedule();
            return w == null || OrderingWindow.menu("window", w.days(), w.from(), w.to(), at.toLocalDateTime());
        }

        List<ModifierGroup> groupsOf(ItemRow i) {
            return i.modifierGroupIds().stream()
                    .map(groups::get)
                    .filter(java.util.Objects::nonNull)
                    .toList();
        }

        static List<PickCheck.Group> pickGroups(List<ModifierGroup> groups) {
            return groups.stream()
                    .map(g -> new PickCheck.Group(
                            java.util.Objects.requireNonNull(g.id()),
                            g.name(),
                            g.rule().rule(),
                            g.rule().count(),
                            g.rule().required(),
                            java.util.Set.copyOf(g.showForOptionIds()),
                            g.options().stream()
                                    .map(o -> new PickCheck.Option(
                                            java.util.Objects.requireNonNull(o.id()), o.name(), o.soldOut()))
                                    .toList()))
                    .toList();
        }

        /** Dishes a slot takes: the listed ones, else every combo-eligible dish of its section. */
        List<ItemRow> eligible(ComboStore.Slot slot) {
            return items.values().stream()
                    .filter(ItemRow::comboEligible)
                    .filter(i -> slot.itemIds().isEmpty()
                            ? i.sectionId().equals(slot.sectionId())
                            : slot.itemIds().contains(i.id()))
                    .toList();
        }
    }

    Snapshot load(String merchantId) {
        var live = menus.menus(merchantId).stream()
                .filter(m -> m.status() == MenuStatus.LIVE)
                .sorted(java.util.Comparator.comparingInt(MenuRow::sort))
                .collect(Collectors.toMap(MenuRow::id, Function.identity(), (a, _) -> a, LinkedHashMap::new));
        var sections = menus.sections(merchantId, null).stream()
                .filter(s -> live.containsKey(s.menuId()))
                .sorted(java.util.Comparator.<SectionRow>comparingInt(
                                s -> List.copyOf(live.keySet()).indexOf(s.menuId()))
                        .thenComparingInt(SectionRow::sort))
                .toList();
        var items = menus.items(merchantId, null).stream()
                .filter(i -> live.containsKey(i.menuId()))
                .filter(i -> i.status() == ItemStatus.PUBLISHED && "approved".equals(i.vetting()))
                .collect(Collectors.toMap(ItemRow::id, Function.identity(), (a, _) -> a, LinkedHashMap::new));
        var byId = groups.groups(merchantId).stream()
                .collect(Collectors.toMap(ModifierGroup::id, Function.identity(), (a, _) -> a));
        var offered = combos.combos(merchantId).stream()
                .filter(c -> c.status() == ComboStatus.LIVE || c.status() == ComboStatus.SCHEDULED)
                .toList();
        return new Snapshot(live, sections, items, byId, offered);
    }
}
