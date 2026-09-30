package ca.northline.food.application;

import ca.northline.food.application.MenuStore.ItemRow;
import ca.northline.food.application.MenuStore.SectionRow;
import ca.northline.food.application.PosImportViews.Counts;
import ca.northline.food.application.PosImportViews.Diff;
import ca.northline.food.application.PosImportViews.GroupChange;
import ca.northline.food.application.PosImportViews.ItemChange;
import ca.northline.food.application.PosImportViews.SectionChange;
import ca.northline.food.application.PosMenuSource.PosGroup;
import ca.northline.food.application.PosMenuSource.PosItem;
import ca.northline.food.application.PosMenuSource.PosMenu;
import ca.northline.food.application.PosStore.Link;
import ca.northline.food.domain.KitchenMessages;
import ca.northline.food.domain.ModifierGroup;
import ca.northline.food.domain.ModifierRule;
import ca.northline.food.domain.PickRule;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;

/**
 * The diff of a POS menu against what earlier imports created (S-36): pure, so the preview and the apply compute the
 * same thing (apply re-plans from the stored menu against the kitchen's data as it is then).
 *
 * <ul>
 *   <li>Groups: POS min/max → the builder's rule (min = max → exactly N, required; min > 0 → at least N, required; else
 *       up to max, optional). No options, or an option over $100 → a problem (skipped; items keep their other groups).
 *   <li>Sections: imported before → matched; a section of this menu with the same name → matched; else new.
 *   <li>Items: no price → problem. Imported before and still in Northline: changed when the POS changed it since the
 *       last import and it differs from Northline (only those fields are written: name, description, price,
 *       modifiers, section — the kitchen's photo, allergens, availability and status stay); else unchanged. Imported
 *       before but deleted in Northline → left out (not imported again). Not in the POS any more → removed (hidden).
 * </ul>
 */
final class PosImportPlanner {
    private PosImportPlanner() {}

    static final int NAME_MAX = 80;
    static final int DESCRIPTION_MAX = 500;
    static final int SECTION_MAX = 60;
    static final int GROUP_NAME_MAX = 40;
    static final long OPTION_MAX_CENTS = 10_000;

    /** The kitchen's data the plan compares with. */
    record Current(
            List<SectionRow> sections,
            Map<String, ItemRow> items,
            Set<String> groupIds,
            Map<String, Link> sectionLinks,
            Map<String, Link> itemLinks,
            Map<String, Link> groupLinks) {}

    static Diff plan(PosMenu menu, Current now) {
        // groups
        var groups = new ArrayList<GroupChange>();
        var groupLocal = new HashMap<String, String>(); // external → local id (existing only)
        var usable = new HashSet<String>();
        for (var g : menu.groups()) {
            var link = now.groupLinks().get(g.externalId());
            var local = link != null && now.groupIds().contains(link.localId()) ? link.localId() : null;
            var rule = rule(g);
            var problem = problem(g);
            if (problem != null) {
                groups.add(new GroupChange(
                        g.externalId(),
                        groupName(g.name()),
                        "problem",
                        rule.rule().code(),
                        rule.count(),
                        rule.required(),
                        g.options().size(),
                        problem));
                continue;
            }
            usable.add(g.externalId());
            if (local != null) {
                groupLocal.put(g.externalId(), local);
            }
            var change = local == null
                    ? "new"
                    : hash(g).equals(Objects.requireNonNull(link).contentHash()) ? "unchanged" : "changed";
            groups.add(new GroupChange(
                    g.externalId(),
                    groupName(g.name()),
                    change,
                    rule.rule().code(),
                    rule.count(),
                    rule.required(),
                    g.options().size(),
                    null));
        }

        // sections
        var sections = new ArrayList<SectionChange>();
        var sectionLocal = new HashMap<String, String>();
        for (var s : menu.sections()) {
            var name = sectionName(s.name());
            var link = now.sectionLinks().get(s.externalId());
            var local = link == null
                    ? null
                    : now.sections().stream()
                            .filter(x -> x.id().equals(link.localId()))
                            .findFirst()
                            .orElse(null);
            if (local == null) {
                local = now.sections().stream()
                        .filter(x -> x.name().strip().equalsIgnoreCase(name))
                        .findFirst()
                        .orElse(null);
            }
            if (local != null) {
                sectionLocal.put(s.externalId(), local.id());
            }
            sections.add(new SectionChange(
                    s.externalId(), name, local == null ? "new" : "matched", local == null ? null : local.id()));
        }

        // items
        var items = new ArrayList<ItemChange>();
        var seen = new HashSet<String>();
        for (var s : menu.sections()) {
            for (var it : s.items()) {
                if (!seen.add(it.externalId())) {
                    continue; // listed in two categories: the first wins
                }
                var name = itemName(it.name());
                var link = now.itemLinks().get(it.externalId());
                var local = link == null ? null : now.items().get(link.localId());
                if (link != null && local == null) {
                    continue; // deleted in Northline: not imported again
                }
                if (it.priceCents() == null || it.priceCents() <= 0) {
                    items.add(new ItemChange(
                            it.externalId(),
                            name,
                            sectionName(s.name()),
                            "problem",
                            List.of(),
                            null,
                            null,
                            KitchenMessages.POS_NO_PRICE,
                            local == null ? null : local.id()));
                    continue;
                }
                var groupsOfItem =
                        it.groupIds().stream().filter(usable::contains).toList();
                if (local == null) {
                    items.add(new ItemChange(
                            it.externalId(),
                            name,
                            sectionName(s.name()),
                            "new",
                            List.of(),
                            it.priceCents(),
                            null,
                            null,
                            null));
                    continue;
                }
                var fields = new ArrayList<String>();
                if (!hash(it, s.externalId())
                        .equals(Objects.requireNonNull(link).contentHash())) {
                    if (!name.equals(local.name())) {
                        fields.add("name");
                    }
                    if (!Objects.equals(description(it.description()), local.description())) {
                        fields.add("description");
                    }
                    if (it.priceCents() != local.priceCents()) {
                        fields.add("price");
                    }
                    var mapped = groupsOfItem.stream()
                            .map(groupLocal::get)
                            .filter(Objects::nonNull)
                            .toList();
                    if (!Set.copyOf(local.modifierGroupIds()).equals(Set.copyOf(mapped))
                            || groupsOfItem.stream().anyMatch(g -> !groupLocal.containsKey(g))) {
                        fields.add("modifiers");
                    }
                    var section = sectionLocal.get(s.externalId());
                    if (section == null || !section.equals(local.sectionId())) {
                        fields.add("section");
                    }
                }
                items.add(new ItemChange(
                        it.externalId(),
                        name,
                        sectionName(s.name()),
                        fields.isEmpty() ? "unchanged" : "changed",
                        fields,
                        it.priceCents(),
                        local.priceCents(),
                        null,
                        local.id()));
            }
        }
        for (var link : now.itemLinks().values()) {
            var local = now.items().get(link.localId());
            if (!seen.contains(link.externalId()) && link.removedAt() == null && local != null) {
                items.add(new ItemChange(
                        link.externalId(),
                        local.name(),
                        null,
                        "removed",
                        List.of(),
                        null,
                        local.priceCents(),
                        null,
                        local.id()));
            }
        }
        var counts = new Counts(
                count(items, "new"),
                count(items, "changed"),
                count(items, "unchanged"),
                count(items, "removed"),
                (int) (items.stream().filter(i -> i.change().equals("problem")).count()
                        + groups.stream()
                                .filter(g -> g.change().equals("problem"))
                                .count()),
                (int) sections.stream().filter(s -> s.change().equals("new")).count(),
                (int) groups.stream().filter(g -> g.change().equals("new")).count(),
                (int) groups.stream().filter(g -> g.change().equals("changed")).count());
        return new Diff(sections, groups, items, counts);
    }

    private static int count(List<ItemChange> items, String change) {
        return (int) items.stream().filter(i -> i.change().equals(change)).count();
    }

    /** POS min/max → the builder's pick rule (count clamped to 1–20 and to the number of options). */
    static ModifierRule rule(PosGroup g) {
        var options = Math.max(1, Math.min(20, g.options().size()));
        var max = g.max();
        if (g.min() > 0 && max != null && max == g.min()) {
            return new ModifierRule(PickRule.EXACTLY, Math.min(options, g.min()), true);
        }
        if (g.min() > 0) {
            return new ModifierRule(PickRule.AT_LEAST, Math.min(options, g.min()), true);
        }
        return new ModifierRule(PickRule.UP_TO, max == null || max <= 0 ? options : Math.min(options, max), false);
    }

    static @Nullable String problem(PosGroup g) {
        if (g.options().isEmpty()) {
            return KitchenMessages.POS_NO_OPTIONS;
        }
        if (g.options().stream().anyMatch(o -> o.priceDeltaCents() < 0 || o.priceDeltaCents() > OPTION_MAX_CENTS)) {
            return KitchenMessages.POS_OPTION_PRICE;
        }
        return null;
    }

    /** The builder's group for a POS group; option ids from {@code optionIds} (external → local, new ones added). */
    static ModifierGroup group(String id, String merchantId, PosGroup g, Map<String, String> optionIds, int sort) {
        var options = new ArrayList<ModifierGroup.Option>();
        for (int i = 0; i < g.options().size(); i++) {
            var o = g.options().get(i);
            options.add(new ModifierGroup.Option(
                    Objects.requireNonNull(optionIds.get(o.externalId())),
                    groupName(o.name()),
                    o.priceDeltaCents(),
                    false,
                    false,
                    i));
        }
        return new ModifierGroup(id, merchantId, groupName(g.name()), rule(g), List.of(), options, sort);
    }

    static String itemName(String raw) {
        return cut(raw, NAME_MAX, "Item");
    }

    static String sectionName(String raw) {
        return cut(raw, SECTION_MAX, "Menu");
    }

    static String groupName(String raw) {
        return cut(raw, GROUP_NAME_MAX, "Options");
    }

    static @Nullable String description(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        var t = raw.strip().replaceAll("\\s+", " ");
        return t.length() > DESCRIPTION_MAX ? t.substring(0, DESCRIPTION_MAX).strip() : t;
    }

    private static String cut(String raw, int max, String fallback) {
        var t = raw.strip().replaceAll("\\s+", " ");
        if (t.isEmpty()) {
            return fallback;
        }
        return t.length() > max ? t.substring(0, max).strip() : t;
    }

    static String hash(PosItem it, String sectionExternalId) {
        return sha(it.name()
                + '\0'
                + Objects.requireNonNullElse(it.description(), "")
                + '\0'
                + it.priceCents()
                + '\0'
                + String.join(",", it.groupIds())
                + '\0'
                + sectionExternalId);
    }

    static String hash(PosGroup g) {
        var sb = new StringBuilder(g.name())
                .append('\0')
                .append(g.min())
                .append('\0')
                .append(g.max());
        g.options()
                .forEach(o -> sb.append('\0')
                        .append(o.externalId())
                        .append('|')
                        .append(o.name())
                        .append('|')
                        .append(o.priceDeltaCents()));
        return sha(sb.toString());
    }

    static String sectionHash(String name) {
        return sha(name.toLowerCase(Locale.ROOT));
    }

    private static String sha(String s) {
        try {
            return HexFormat.of()
                    .formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    static Optional<PosGroup> groupOf(PosMenu menu, String externalId) {
        return menu.groups().stream()
                .filter(g -> g.externalId().equals(externalId))
                .findFirst();
    }
}
