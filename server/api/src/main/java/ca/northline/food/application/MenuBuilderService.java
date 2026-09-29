package ca.northline.food.application;

import ca.northline.food.api.MenuItemAvailabilityChanged;
import ca.northline.food.api.MenuPublished;
import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.application.MenuStore.MenuRow;
import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.application.MenuUseCases.EditMenuItems;
import ca.northline.food.application.MenuUseCases.EditMenus;
import ca.northline.food.application.MenuUseCases.EditSections;
import ca.northline.food.application.MenuUseCases.ItemCommand;
import ca.northline.food.application.MenuUseCases.ListMenus;
import ca.northline.food.application.MenuUseCases.MenuItemPhotos;
import ca.northline.food.application.MenuUseCases.ViewMenu;
import ca.northline.food.application.MenuViews.ItemView;
import ca.northline.food.application.MenuViews.MenuDetail;
import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.application.MenuViews.MenuSummary;
import ca.northline.food.application.MenuViews.ModifierRef;
import ca.northline.food.application.MenuViews.Photo;
import ca.northline.food.application.MenuViews.SectionDetail;
import ca.northline.food.application.MenuViews.SectionRef;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemVisibility;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.KitchenTime;
import ca.northline.food.domain.MenuStatus;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.OpeningRanges;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import javax.imageio.ImageIO;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class MenuBuilderService
        implements ListMenus, ViewMenu, EditMenus, EditSections, EditMenuItems, MenuItemPhotos, KitchenProvisioning {

    static final Set<String> PHOTO_TYPES = Set.of("image/jpeg", "image/png", "image/webp");
    static final int PHOTO_MAX_BYTES = 10 * 1024 * 1024;
    static final int PHOTO_MIN_PX = 1000;
    static final List<String> STARTER_SECTIONS = List.of("Starters", "Mains", "Drinks", "Dessert");

    private final MenuStore menus;
    private final ModifierGroupStore groups;
    private final KitchenMerchantFacts merchant;
    private final KitchenPhotoStore photos;
    private final ApplicationEventPublisher events;
    private final Clock clock;

    // ── menus ─────────────────────────────────────────────────────────────────

    @Override
    public List<MenuSummary> menus(String merchantId) {
        var sections = menus.sections(merchantId, null);
        var counts = menus.items(merchantId, null).stream()
                .collect(Collectors.groupingBy(ItemRow::sectionId, Collectors.counting()));
        return menus.menus(merchantId).stream()
                .map(m -> summary(
                        m,
                        sections.stream()
                                .filter(s -> s.menuId().equals(m.id()))
                                .map(s -> new SectionRef(
                                        s.id(),
                                        s.name(),
                                        s.sort(),
                                        counts.getOrDefault(s.id(), 0L).intValue()))
                                .toList()))
                .toList();
    }

    @Override
    public MenuDetail menu(String merchantId, String menuId) {
        var menu = requireMenu(merchantId, menuId);
        var approved = merchant.approved(merchantId);
        var names = groupNames(merchantId);
        var today = KitchenTime.today(clock);
        var items = menus.items(merchantId, menuId);
        return new MenuDetail(
                menu.id(),
                menu.name(),
                menu.status(),
                menu.schedule(),
                menu.publishedAt(),
                approved,
                menus.sections(merchantId, menuId).stream()
                        .map(s -> new SectionDetail(
                                s.id(),
                                s.name(),
                                s.sort(),
                                items.stream()
                                        .filter(i -> i.sectionId().equals(s.id()))
                                        .map(i -> view(i, approved, names, today))
                                        .toList()))
                        .toList());
    }

    @Override
    @Transactional
    public MenuSummary create(String merchantId, String name) {
        var sort =
                menus.menus(merchantId).stream().mapToInt(MenuRow::sort).max().orElse(-1) + 1;
        var menu = new MenuRow(
                Ids.next(), merchantId, name.strip(), MenuStatus.DRAFT, MenuSchedule.OPEN_HOURS, sort, null);
        menus.insertMenu(menu);
        return summary(menu, List.of());
    }

    @Override
    @Transactional
    public MenuSummary rename(String merchantId, String menuId, String name) {
        var menu =
                requireMenu(merchantId, menuId).toBuilder().name(name.strip()).build();
        menus.updateMenu(menu);
        return summaryOf(merchantId, menu);
    }

    @Override
    @Transactional
    public MenuSummary schedule(String merchantId, String menuId, MenuSchedule schedule) {
        var before = requireMenu(merchantId, menuId);
        var menu = before.toBuilder().schedule(checked(schedule)).build();
        menus.updateMenu(menu);
        return summaryOf(merchantId, menu);
    }

    @Override
    @Transactional
    public MenuSummary publish(String merchantId, String menuId, String actorId) {
        var menu = requireMenu(merchantId, menuId);
        if (!merchant.approved(merchantId)) {
            throw new Conflict("not_approved", "Your menu goes live once Northline approves your kitchen.");
        }
        if (menu.status() == MenuStatus.LIVE) {
            return summaryOf(merchantId, menu);
        }
        var now = clock.instant();
        var live = menu.toBuilder().status(MenuStatus.LIVE).publishedAt(now).build();
        menus.updateMenu(live);
        events.publishEvent(new MenuPublished(Ids.next(), now, menuId, merchantId, actorId));
        return summaryOf(merchantId, live);
    }

    @Override
    @Transactional
    public MenuSummary hide(String merchantId, String menuId) {
        var menu = requireMenu(merchantId, menuId);
        var hidden = menu.status() == MenuStatus.LIVE
                ? menu.toBuilder().status(MenuStatus.HIDDEN).build()
                : menu;
        menus.updateMenu(hidden);
        return summaryOf(merchantId, hidden);
    }

    // ── sections ──────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public SectionRef add(String merchantId, String menuId, String name) {
        requireMenu(merchantId, menuId);
        var sort = menus.sections(merchantId, menuId).stream()
                        .mapToInt(SectionRow::sort)
                        .max()
                        .orElse(-1)
                + 1;
        var section = new SectionRow(Ids.next(), menuId, name.strip(), sort);
        menus.insertSection(section);
        return new SectionRef(section.id(), section.name(), sort, 0);
    }

    @Override
    @Transactional
    public SectionRef rename(String merchantId, String menuId, String sectionId, String name) {
        var section = menus.sections(merchantId, menuId).stream()
                .filter(s -> s.id().equals(sectionId))
                .findFirst()
                .orElseThrow(() -> new NotFound("section", sectionId));
        var renamed = new SectionRow(section.id(), menuId, name.strip(), section.sort());
        menus.updateSection(renamed);
        var count = (int) menus.items(merchantId, menuId).stream()
                .filter(i -> i.sectionId().equals(sectionId))
                .count();
        return new SectionRef(sectionId, renamed.name(), section.sort(), count);
    }

    @Override
    @Transactional
    public List<SectionRef> reorder(String merchantId, String menuId, List<String> sectionIds) {
        requireMenu(merchantId, menuId);
        var current =
                menus.sections(merchantId, menuId).stream().map(SectionRow::id).collect(Collectors.toSet());
        if (sectionIds.size() != current.size() || !current.equals(new HashSet<>(sectionIds))) {
            throw RuleViolation.of("sectionIds", "complete", "Send every section of the menu, in the new order.");
        }
        menus.reorderSections(menuId, sectionIds);
        return menus(merchantId).stream()
                .filter(m -> m.id().equals(menuId))
                .findFirst()
                .map(MenuSummary::sections)
                .orElse(List.of());
    }

    // ── items ─────────────────────────────────────────────────────────────────

    @Override
    @Transactional
    public ItemView create(String merchantId, ItemCommand command) {
        var section = validate(merchantId, command);
        var sort = (int) menus.items(merchantId, command.menuId()).stream()
                .filter(i -> i.sectionId().equals(section.id()))
                .count();
        var row = rowOf(merchantId, Ids.next(), command, sort, null);
        menus.insertItem(row);
        return afterWrite(row, false);
    }

    @Override
    @Transactional
    public ItemView update(String merchantId, String itemId, ItemCommand command) {
        var before = requireItem(merchantId, itemId);
        validate(merchantId, command);
        var sort = before.sectionId().equals(command.sectionId())
                ? before.sort()
                : (int) menus.items(merchantId, command.menuId()).stream()
                        .filter(i -> i.sectionId().equals(command.sectionId()))
                        .count();
        var row = rowOf(merchantId, itemId, command, sort, before);
        menus.updateItem(row);
        return afterWrite(row, visible(before, merchant.approved(merchantId)));
    }

    @Override
    @Transactional
    public ItemView soldOut(String merchantId, String itemId, boolean soldOut) {
        var before = requireItem(merchantId, itemId);
        var today = KitchenTime.today(clock);
        var row = before.toBuilder()
                .soldOutOn(soldOut ? today : null)
                .available(!soldOut)
                .build();
        menus.updateItem(row);
        var approved = merchant.approved(merchantId);
        publishAvailability(row, approved, today);
        return view(row, approved, groupNames(merchantId), today);
    }

    @Override
    @Transactional
    public void delete(String merchantId, String itemId) {
        requireItem(merchantId, itemId);
        menus.deleteItem(merchantId, itemId);
    }

    @Override
    @Transactional
    public ItemView upload(String merchantId, String itemId, byte[] bytes, String contentType) {
        var before = requireItem(merchantId, itemId);
        if (!PHOTO_TYPES.contains(contentType) || bytes.length == 0 || bytes.length > PHOTO_MAX_BYTES) {
            throw RuleViolation.of("file", "file", KitchenMessages.PHOTO_FILE);
        }
        checkSize(bytes, contentType);
        var key = "menu-items/%s/%s/%s".formatted(merchantId, itemId, Ids.next());
        photos.put(key, bytes, contentType);
        var row = before.toBuilder().photoKey(key).photoContentType(contentType).build();
        var approved = merchant.approved(merchantId);
        var withVetting = row.withVetting(visibility(row, approved).vetting());
        menus.updateItem(withVetting);
        return afterWrite(withVetting, visible(before, approved));
    }

    @Override
    public Optional<Photo> photo(String merchantId, String itemId) {
        var item = requireItem(merchantId, itemId);
        if (item.photoKey() == null) {
            return Optional.empty();
        }
        return photos.get(item.photoKey())
                .map(b -> new Photo(b, Objects.requireNonNullElse(item.photoContentType(), "image/jpeg")));
    }

    // ── provisioning (listeners) ──────────────────────────────────────────────

    @Override
    @Transactional
    public void starterMenu(String merchantId) {
        if (!menus.menus(merchantId).isEmpty()) {
            return;
        }
        var menu =
                new MenuRow(Ids.next(), merchantId, "Dinner menu", MenuStatus.DRAFT, MenuSchedule.OPEN_HOURS, 0, null);
        menus.insertMenu(menu);
        for (int i = 0; i < STARTER_SECTIONS.size(); i++) {
            menus.insertSection(new SectionRow(Ids.next(), menu.id(), STARTER_SECTIONS.get(i), i));
        }
    }

    @Override
    @Transactional
    public void reaudit(String merchantId) {
        var approved = merchant.approved(merchantId);
        var today = KitchenTime.today(clock);
        for (var item : menus.publishedItems(merchantId)) {
            var vetting = visibility(item, approved).vetting();
            if (!vetting.equals(item.vetting())) {
                var row = item.withVetting(vetting);
                menus.updateItem(row);
                publishAvailability(row, approved, today);
            }
        }
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /** open_hours · window (days + from–to) · quote (notice hours). */
    private static MenuSchedule checked(MenuSchedule s) {
        var errors = new ArrayList<Violation>();
        switch (s.mode()) {
            case "open_hours" -> {
                return MenuSchedule.OPEN_HOURS;
            }
            case "window" -> {
                if (s.days().isEmpty() || s.days().stream().anyMatch(d -> d < 1 || d > 7)) {
                    errors.add(new Violation("days", "required", KitchenMessages.DAYS));
                }
                var from = Objects.requireNonNullElse(s.from(), "");
                var to = Objects.requireNonNullElse(s.to(), "");
                var ranges = OpeningRanges.check("hours", List.of(List.of(from, to)), errors);
                if (!errors.isEmpty()) {
                    throw new RuleViolation(errors);
                }
                var range = ranges.asText().getFirst();
                return new MenuSchedule(
                        "window", s.days().stream().distinct().sorted().toList(), range.get(0), range.get(1), null);
            }
            case "quote" -> {
                if (s.noticeHours() == null || s.noticeHours() < 1 || s.noticeHours() > 336) {
                    throw RuleViolation.of("noticeHours", "range", KitchenMessages.NOTICE_HOURS);
                }
                return new MenuSchedule("quote", List.of(), null, null, s.noticeHours());
            }
            default -> throw RuleViolation.of("mode", "option", KitchenMessages.OPTION);
        }
    }

    private SectionRow validate(String merchantId, ItemCommand command) {
        var section = menus.sections(merchantId, command.menuId()).stream()
                .filter(s -> s.id().equals(command.sectionId()))
                .findFirst()
                .orElse(null);
        var owned = groups.owned(merchantId, command.modifierGroupIds());
        var errors = new ArrayList<Violation>();
        if (menus.menu(merchantId, command.menuId()).isEmpty()) {
            errors.add(new Violation("menuId", "exists", KitchenMessages.MENU_AND_SECTION));
        }
        MenuItemRules.check("", command, section, owned, errors);
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }
        return Objects.requireNonNull(section);
    }

    private ItemRow rowOf(String merchantId, String id, ItemCommand c, int sort, @Nullable ItemRow before) {
        var status = c.publish() ? ItemStatus.PUBLISHED : ItemStatus.DRAFT;
        var draft = ItemRow.builder()
                .id(id)
                .merchantId(merchantId)
                .menuId(c.menuId())
                .sectionId(c.sectionId())
                .name(c.name().strip())
                .description(
                        c.description() == null || c.description().isBlank()
                                ? null
                                : c.description().strip())
                .priceCents(c.priceCents())
                .allergens(MenuItemRules.allergens(c.allergens()))
                .dietary(MenuItemRules.distinct(c.dietary()))
                .prepAddMin(c.prepAddMin())
                .dailyLimit(c.dailyLimit())
                .soldToday(before == null ? 0 : before.soldToday())
                .soldOutOn(before == null ? null : before.soldOutOn())
                .availability(c.availability())
                .comboEligible(c.comboEligible())
                .status(status)
                .vetting("draft")
                .available(before == null || before.available())
                .photoKey(before == null ? null : before.photoKey())
                .photoContentType(before == null ? null : before.photoContentType())
                .sort(sort)
                .modifierGroupIds(MenuItemRules.distinct(c.modifierGroupIds()))
                .publishedAt(
                        status == ItemStatus.PUBLISHED
                                ? (before != null && before.publishedAt() != null
                                        ? before.publishedAt()
                                        : clock.instant())
                                : null)
                .updatedAt(clock.instant())
                .build();
        return draft.withVetting(
                visibility(draft, merchant.approved(merchantId)).vetting());
    }

    private ItemView afterWrite(ItemRow row, boolean wasVisible) {
        var approved = merchant.approved(row.merchantId());
        var today = KitchenTime.today(clock);
        if (visible(row, approved) != wasVisible) {
            publishAvailability(row, approved, today);
        }
        return view(row, approved, groupNames(row.merchantId()), today);
    }

    private void publishAvailability(ItemRow row, boolean approved, LocalDate today) {
        events.publishEvent(new MenuItemAvailabilityChanged(
                Ids.next(),
                clock.instant(),
                row.id(),
                row.merchantId(),
                row.menuId(),
                visible(row, approved),
                soldOut(row, today) ? today.toString() : null));
    }

    /** Customers can order it: live item on a live menu, not sold out. */
    private boolean visible(ItemRow row, boolean approved) {
        return visibility(row, approved) == ItemVisibility.LIVE
                && !soldOut(row, KitchenTime.today(clock))
                && menus.menu(row.merchantId(), row.menuId())
                        .map(m -> m.status() == MenuStatus.LIVE)
                        .orElse(false);
    }

    private static ItemVisibility visibility(ItemRow row, boolean approved) {
        return ItemVisibility.of(row.status(), row.allergens() != null, row.photoKey() != null, approved);
    }

    private static boolean soldOut(ItemRow row, LocalDate today) {
        return (row.soldOutOn() != null && !row.soldOutOn().isBefore(today))
                || (row.dailyLimit() != null && row.soldToday() >= row.dailyLimit());
    }

    private ItemView view(ItemRow i, boolean approved, Map<String, String> names, LocalDate today) {
        return new ItemView(
                i.id(),
                i.menuId(),
                i.sectionId(),
                i.name(),
                i.description(),
                i.priceCents(),
                i.allergens(),
                i.dietary(),
                i.prepAddMin(),
                i.dailyLimit(),
                i.soldToday(),
                soldOut(i, today),
                i.availability(),
                i.comboEligible(),
                i.modifierGroupIds().stream()
                        .filter(names::containsKey)
                        .map(id -> new ModifierRef(id, Objects.requireNonNull(names.get(id))))
                        .toList(),
                i.status(),
                visibility(i, approved),
                i.photoKey() != null,
                i.updatedAt());
    }

    private Map<String, String> groupNames(String merchantId) {
        return groups.groups(merchantId).stream()
                .collect(Collectors.toMap(ModifierGroup::id, ModifierGroup::name, (a, _) -> a));
    }

    private MenuSummary summaryOf(String merchantId, MenuRow menu) {
        return menus(merchantId).stream()
                .filter(m -> m.id().equals(menu.id()))
                .findFirst()
                .orElseGet(() -> summary(menu, List.of()));
    }

    private static MenuSummary summary(MenuRow m, List<SectionRef> sections) {
        return new MenuSummary(m.id(), m.name(), m.status(), m.schedule(), m.sort(), m.publishedAt(), sections);
    }

    private MenuRow requireMenu(String merchantId, String menuId) {
        return menus.menu(merchantId, menuId).orElseThrow(() -> new NotFound("menu", menuId));
    }

    private ItemRow requireItem(String merchantId, String itemId) {
        return menus.item(merchantId, itemId).orElseThrow(() -> new NotFound("menu item", itemId));
    }

    /** "≥1000 px": JPEG / PNG are measured; WebP (no JDK reader) is accepted as uploaded. */
    private static void checkSize(byte[] bytes, String contentType) {
        if (contentType.equals("image/webp")) {
            return;
        }
        try {
            var image = ImageIO.read(new ByteArrayInputStream(bytes));
            if (image == null) {
                throw RuleViolation.of("file", "file", KitchenMessages.PHOTO_FILE);
            }
            if (Math.min(image.getWidth(), image.getHeight()) < PHOTO_MIN_PX) {
                throw RuleViolation.of("file", "size", "Use a photo at least 1000 px on the short side.");
            }
        } catch (IOException ex) {
            throw RuleViolation.of("file", "file", KitchenMessages.PHOTO_FILE);
        }
    }
}
