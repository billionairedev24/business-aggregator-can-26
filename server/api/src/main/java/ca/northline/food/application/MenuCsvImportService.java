package ca.northline.food.application;

import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.application.MenuUseCases.ImportMenuCsv;
import ca.northline.food.application.MenuUseCases.ItemCommand;
import ca.northline.food.application.MenuViews.ImportResult;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * "Import from POS / CSV" → CSV. Columns (header row, any order, case-insensitive): {@code section, name, price,
 * allergens} required; {@code description, dietary, prep_add_min} optional. Lists are separated by {@code ;} or
 * {@code |}; allergens {@code none} = declared none. Unknown sections are created at the end of the menu. Items arrive
 * as drafts (photos are added in the editor). All rows or none: errors come back as {@code rows[<line>].<field>}.
 */
@Service
@RequiredArgsConstructor
class MenuCsvImportService implements ImportMenuCsv {

    static final int MAX_BYTES = 1024 * 1024;
    static final int MAX_ROWS = 500;
    static final int MAX_ERRORS = 20;
    private static final List<String> REQUIRED = List.of("section", "name", "price", "allergens");

    private final MenuStore menus;
    private final Clock clock;

    @Override
    @Transactional
    public ImportResult importCsv(String merchantId, String menuId, byte[] csv) {
        if (menus.menu(merchantId, menuId).isEmpty()) {
            throw new NotFound("menu", menuId);
        }
        if (csv.length == 0 || csv.length > MAX_BYTES) {
            throw RuleViolation.of("file", "file", KitchenMessages.CSV_FILE);
        }
        var lines = KitchenCsv.parse(new String(csv, StandardCharsets.UTF_8));
        if (lines.isEmpty()) {
            throw RuleViolation.of("file", "header", KitchenMessages.CSV_HEADER);
        }
        var header = lines.getFirst().stream()
                .map(h -> h.strip().toLowerCase(Locale.ROOT))
                .toList();
        if (!header.containsAll(REQUIRED)) {
            throw RuleViolation.of("file", "header", KitchenMessages.CSV_HEADER);
        }
        var rows = lines.subList(1, lines.size()).stream()
                .filter(r -> r.stream().anyMatch(c -> !c.isBlank()))
                .toList();
        if (rows.isEmpty()) {
            throw RuleViolation.of("file", "empty", KitchenMessages.CSV_EMPTY);
        }
        if (rows.size() > MAX_ROWS) {
            throw RuleViolation.of("file", "too_many", KitchenMessages.CSV_TOO_MANY);
        }

        var sections = new LinkedHashMap<String, SectionRow>();
        menus.sections(merchantId, menuId).forEach(s -> sections.putIfAbsent(key(s.name()), s));
        var nextSort =
                sections.values().stream().mapToInt(SectionRow::sort).max().orElse(-1) + 1;
        var newSections = new ArrayList<SectionRow>();
        var errors = new ArrayList<Violation>();
        var commands = new ArrayList<ItemCommand>();
        for (int i = 0; i < rows.size() && errors.size() < MAX_ERRORS; i++) {
            var cell = cells(header, rows.get(i));
            var prefix = "rows[" + (i + 2) + "].";
            var sectionName = cell.getOrDefault("section", "").strip();
            if (sectionName.isEmpty() || sectionName.length() > 60) {
                errors.add(new Violation(prefix + "section", "required", KitchenMessages.SECTION_NAME));
                continue;
            }
            var section = sections.get(key(sectionName));
            if (section == null) {
                section = new SectionRow(Ids.next(), menuId, sectionName, nextSort++);
                sections.put(key(sectionName), section);
                newSections.add(section);
            }
            var price = cents(cell.getOrDefault("price", ""));
            var allergensText = cell.getOrDefault("allergens", "").strip();
            if (allergensText.isEmpty()) {
                errors.add(new Violation(prefix + "allergens", "required", KitchenMessages.ALLERGENS));
                continue;
            }
            var name = cell.getOrDefault("name", "").strip();
            if (name.length() > 80) {
                errors.add(new Violation(prefix + "name", "length", KitchenMessages.AT_MOST_80));
                continue;
            }
            var description = cell.getOrDefault("description", "").strip();
            if (description.length() > 500) {
                errors.add(new Violation(prefix + "description", "length", KitchenMessages.AT_MOST_500));
                continue;
            }
            var prep = prep(cell.getOrDefault("prep_add_min", ""));
            var command = new ItemCommand(
                    menuId,
                    section.id(),
                    name,
                    description,
                    price == null ? 0 : price,
                    prep == null ? -1 : prep,
                    allergensText.equalsIgnoreCase("none") ? List.of() : codes(allergensText),
                    codes(cell.getOrDefault("dietary", "")),
                    List.of(),
                    ItemWindow.ALWAYS,
                    null,
                    true,
                    false,
                    null);
            MenuItemRules.check(prefix, command, section, Set.of(), errors);
            commands.add(command);
        }
        if (!errors.isEmpty()) {
            throw new RuleViolation(errors);
        }

        newSections.forEach(menus::insertSection);
        var sortBySection = new LinkedHashMap<String, Integer>();
        menus.items(merchantId, menuId).forEach(it -> sortBySection.merge(it.sectionId(), 1, Integer::sum));
        var now = clock.instant();
        for (var c : commands) {
            int sort = sortBySection.merge(c.sectionId(), 1, Integer::sum) - 1;
            menus.insertItem(ItemRow.builder()
                    .id(Ids.next())
                    .merchantId(merchantId)
                    .menuId(menuId)
                    .sectionId(c.sectionId())
                    .name(c.name())
                    .description(c.description() == null || c.description().isEmpty() ? null : c.description())
                    .priceCents(c.priceCents())
                    .allergens(MenuItemRules.allergens(c.allergens()))
                    .dietary(MenuItemRules.distinct(c.dietary()))
                    .prepAddMin(c.prepAddMin())
                    .soldToday(0)
                    .availability(ItemWindow.ALWAYS)
                    .comboEligible(true)
                    .status(ItemStatus.DRAFT)
                    .vetting("draft")
                    .available(true)
                    .sort(sort)
                    .modifierGroupIds(List.of())
                    .updatedAt(now)
                    .build());
        }
        return new ImportResult(commands.size(), newSections.size());
    }

    private static Map<String, String> cells(List<String> header, List<String> row) {
        var out = new LinkedHashMap<String, String>();
        for (int c = 0; c < header.size() && c < row.size(); c++) {
            out.putIfAbsent(header.get(c), row.get(c));
        }
        return out;
    }

    private static String key(String name) {
        return name.strip().toLowerCase(Locale.ROOT);
    }

    /** "17", "17.50", "$17.50" → cents; anything else → null. */
    static @Nullable Long cents(String text) {
        var t = text.strip().replace("$", "").replace(",", "");
        if (!t.matches("^\\d{1,5}(\\.\\d{1,2})?$")) {
            return null;
        }
        return new BigDecimal(t).movePointRight(2).longValueExact();
    }

    private static @Nullable Integer prep(String text) {
        var t = text.strip();
        if (t.isEmpty()) {
            return 0;
        }
        return t.matches("^\\d{1,2}$") ? Integer.valueOf(t) : null;
    }

    /** "Tree nuts; wheat" → ["tree_nuts", "wheat"]. */
    static List<String> codes(String text) {
        return Arrays.stream(text.split("[;|]"))
                .map(s -> s.strip().toLowerCase(Locale.ROOT).replaceAll("[\\s-]+", "_"))
                .filter(s -> !s.isEmpty())
                .toList();
    }
}
