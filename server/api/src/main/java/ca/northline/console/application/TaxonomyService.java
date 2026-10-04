package ca.northline.console.application;

import ca.northline.catalogue.api.TaxonomyAdmin;
import ca.northline.catalogue.api.TaxonomyAdmin.Category;
import ca.northline.catalogue.api.TaxonomyAdmin.CategoryInput;
import ca.northline.catalogue.api.TaxonomyAdmin.RegulatorInput;
import ca.northline.developer.api.AuditTrail;
import ca.northline.merchants.api.MerchantCategories;
import ca.northline.merchants.api.MerchantCategories.Limit;
import ca.northline.merchants.api.MerchantCategories.Usage;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Clock;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link ManageTaxonomy}: catalogue edits the taxonomy, merchants holds the businesses' categories, this audits. */
@Service
@RequiredArgsConstructor
@Transactional
class TaxonomyService implements ManageTaxonomy {

    private final TaxonomyAdmin taxonomy;
    private final MerchantCategories merchantCategories;
    private final AuditTrail audit;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Screen screen() {
        var all = taxonomy.taxonomy();
        var usage = merchantCategories.usage();
        var rows = all.categories().stream().map(c -> row(c, usage)).toList();
        var used = new HashMap<String, Long>();
        all.categories()
                .forEach(c -> c.regulators().forEach(r -> {
                    if (r.regulator() != null) {
                        used.merge(r.regulator(), 1L, Long::sum);
                    }
                }));
        return new Screen(
                clock.instant(),
                all.categories().stream()
                        .filter(c -> "service".equals(c.root()) && !c.group())
                        .count(),
                all.categories().stream()
                        .filter(c -> "shop".equals(c.root()) && c.group())
                        .count(),
                rows,
                all.regulators().stream()
                        .map(r -> new RegulatorRow(
                                r.code(), r.name(), r.province(), r.website(), used.getOrDefault(r.code(), 0L)))
                        .toList(),
                merchantCategories.limits(),
                merchantCategories.suggestions());
    }

    @Override
    public Row createCategory(CategoryInput input, Actor actor) {
        var created = taxonomy.create(input, actor.userId());
        record(actor, "catalogue.category_created", "category", created.id(), null, Map.of("root", created.root()));
        return row(created);
    }

    @Override
    public Row updateCategory(String id, CategoryInput input, Actor actor) {
        var change = taxonomy.update(id, input, actor.userId());
        record(actor, "catalogue.category_updated", "category", id, null, Map.of("fields", changed(change)));
        return row(change.after());
    }

    @Override
    public Row regulate(String id, String province, @Nullable String regulator, Actor actor) {
        var change = taxonomy.regulate(id, province, regulator, actor.userId());
        record(
                actor,
                "catalogue.category_regulated",
                "category",
                id,
                rule(change.before(), province),
                rule(change.after(), province));
        return row(change.after());
    }

    @Override
    public Row classify(String id, @Nullable String ageClass, Actor actor) {
        var change = taxonomy.classify(id, ageClass, actor.userId());
        var before = new LinkedHashMap<String, Object>();
        before.put(
                "ageClass",
                change.before().ageClass() == null ? "none" : change.before().ageClass());
        var after = new LinkedHashMap<String, Object>();
        after.put(
                "ageClass",
                change.after().ageClass() == null ? "none" : change.after().ageClass());
        record(actor, "catalogue.category_age_class", "category", id, before, after);
        return row(change.after());
    }

    @Override
    public RegulatorRow createRegulator(RegulatorInput input, Actor actor) {
        var r = taxonomy.createRegulator(input, actor.userId());
        record(actor, "catalogue.regulator_created", "regulator", r.code(), null, Map.of("province", r.province()));
        return new RegulatorRow(r.code(), r.name(), r.province(), r.website(), 0);
    }

    @Override
    public RegulatorRow updateRegulator(String code, RegulatorInput input, Actor actor) {
        var change = taxonomy.updateRegulator(code, input, actor.userId());
        record(
                actor,
                "catalogue.regulator_updated",
                "regulator",
                code,
                Map.of("province", change.before().province()),
                Map.of("province", change.after().province()));
        var after = change.after();
        var categories = taxonomy.taxonomy().categories().stream()
                .filter(c -> c.regulators().stream().anyMatch(r -> code.equals(r.regulator())))
                .count();
        return new RegulatorRow(after.code(), after.name(), after.province(), after.website(), categories);
    }

    @Override
    public Limit setLimit(String merchantType, int max, Actor actor) {
        var before = merchantCategories.limits().stream()
                .filter(l -> l.merchantType().equals(merchantType))
                .findFirst();
        var after = merchantCategories.setLimit(merchantType, max, actor.userId());
        record(
                actor,
                "merchants.category_limit_changed",
                "category_limit",
                merchantType,
                before.<Map<String, ?>>map(l -> Map.of("max", l.max())).orElse(null),
                Map.of("max", after.max()));
        return after;
    }

    @Override
    public Resolved approveSuggestion(String suggestionId, CategoryInput input, Actor actor) {
        var suggestion = merchantCategories.suggestions().stream()
                .filter(s -> s.id().equals(suggestionId))
                .findFirst()
                .orElseThrow(() -> new NotFound("suggestion", suggestionId));
        var named = input.nameEn() == null || input.nameEn().isBlank()
                ? new CategoryInput(
                        input.root(),
                        input.parentId(),
                        suggestion.name(),
                        input.nameFr(),
                        input.bookingType(),
                        input.regulatedRegistry(),
                        input.requiresVsCheck())
                : input;
        var created = taxonomy.create(named, actor.userId());
        record(actor, "catalogue.category_created", "category", created.id(), null, Map.of("root", created.root()));
        return resolve(suggestionId, created, "catalogue.suggestion_approved", actor);
    }

    @Override
    public Resolved mergeSuggestion(String suggestionId, String categoryId, Actor actor) {
        if (categoryId.isBlank()) {
            throw RuleViolation.of("categoryId", "required", CATEGORY_REQUIRED);
        }
        var category = taxonomy.category(categoryId)
                .orElseThrow(() -> RuleViolation.of("categoryId", "unknown", CATEGORY_REQUIRED));
        return resolve(suggestionId, category, "catalogue.suggestion_merged", actor);
    }

    private Resolved resolve(String suggestionId, Category category, String action, Actor actor) {
        var resolution = merchantCategories.resolve(suggestionId, category.id(), regulated(category), actor.userId());
        record(
                actor,
                action,
                "suggestion",
                suggestionId,
                null,
                Map.of(
                        "category", category.id(),
                        "moved", resolution.moved().size(),
                        "alreadyHeld", resolution.alreadyHeld().size()));
        for (var merchantId : resolution.moved()) {
            audit.record(new AuditTrail.Entry(
                    merchantId,
                    actor.userId(),
                    actor.roles(),
                    "merchant.category_assigned",
                    "merchant_category",
                    category.id(),
                    Map.of("category", suggestionId),
                    Map.of("category", category.id())));
        }
        return new Resolved(
                row(category),
                resolution.moved().size(),
                resolution.alreadyHeld().size());
    }

    /** A category is regulated when it has a default licence registry or a regulator in some province. */
    private static boolean regulated(Category c) {
        return c.regulatedRegistry() != null || c.regulators().stream().anyMatch(r -> r.regulator() != null);
    }

    private Row row(Category c) {
        return row(c, merchantCategories.usage());
    }

    private static Row row(Category c, Map<String, Usage> usage) {
        var u = usage.get(c.id());
        return new Row(
                c.id(),
                c.parentId(),
                c.root(),
                c.group(),
                c.nameEn(),
                c.nameFr(),
                c.bookingType(),
                c.regulatedRegistry(),
                c.requiresVsCheck(),
                c.regulators(),
                u == null ? 0 : u.businesses(),
                u == null ? List.of() : u.provinces().stream().sorted().toList(),
                c.liveListings(),
                c.medianPriceCents(),
                c.priceMode(),
                c.ageClass());
    }

    private static Map<String, ?> rule(Category c, String province) {
        var out = new LinkedHashMap<String, Object>();
        out.put("province", province);
        c.regulators().stream()
                .filter(r -> r.province().equals(province))
                .findFirst()
                .ifPresentOrElse(
                        r -> out.put("regulator", r.regulator() == null ? TaxonomyAdmin.NONE : r.regulator()),
                        () -> out.put("regulator", "default"));
        return out;
    }

    /** The names of the fields an edit changed (codes only: the names themselves stay out of the audit log). */
    private static List<String> changed(TaxonomyAdmin.Change<Category> change) {
        var b = change.before();
        var a = change.after();
        var out = new java.util.ArrayList<String>();
        if (!b.nameEn().equals(a.nameEn())) {
            out.add("nameEn");
        }
        if (!java.util.Objects.equals(b.nameFr(), a.nameFr())) {
            out.add("nameFr");
        }
        if (!java.util.Objects.equals(b.bookingType(), a.bookingType())) {
            out.add("bookingType");
        }
        if (!java.util.Objects.equals(b.regulatedRegistry(), a.regulatedRegistry())) {
            out.add("regulatedRegistry");
        }
        if (b.requiresVsCheck() != a.requiresVsCheck()) {
            out.add("requiresVsCheck");
        }
        return out;
    }

    private void record(
            Actor actor,
            String action,
            String targetType,
            String targetId,
            @Nullable Map<String, ?> before,
            @Nullable Map<String, ?> after) {
        audit.record(
                new AuditTrail.Entry(null, actor.userId(), actor.roles(), action, targetType, targetId, before, after));
    }
}
