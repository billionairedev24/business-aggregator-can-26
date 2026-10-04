package ca.northline.catalogue.application;

import ca.northline.catalogue.api.TaxonomyAdmin;
import ca.northline.region.api.AgeClass;
import ca.northline.region.api.Regions;
import ca.northline.shared.Conflict;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import ca.northline.shared.RuleViolation.Violation;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** {@link TaxonomyAdmin} (S-94): validation, ids and the province rules; the console audits. */
@Service
@RequiredArgsConstructor
@Transactional
class TaxonomyAdminService implements TaxonomyAdmin {

    static final Set<String> ROOTS = Set.of("service", "shop", "food");
    static final Set<String> BOOKING_TYPES = Set.of("visit", "home", "event", "appointment", "consult");
    private static final Pattern CODE = Pattern.compile("[a-z0-9][a-z0-9_-]{1,39}");

    private final TaxonomyStore store;
    private final Regions regions;
    private final Clock clock;

    @Override
    @Transactional(readOnly = true)
    public Taxonomy taxonomy() {
        return new Taxonomy(store.categories(), store.regulators());
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Category> category(String id) {
        return store.category(id);
    }

    @Override
    public Category create(CategoryInput input, String actorId) {
        var problems = new ArrayList<Violation>();
        var parentId = blank(input.parentId());
        String root;
        if (parentId != null) {
            var parent = store.category(parentId).filter(Category::group);
            if (parent.isEmpty()
                    || (input.root() != null && !parent.get().root().equals(input.root()))) {
                problems.add(new Violation("parentId", "unknown", PARENT));
            }
            root = parent.map(Category::root).orElse("");
        } else {
            root = input.root() == null ? "" : input.root();
            if (!ROOTS.contains(root)) {
                problems.add(new Violation("root", "unknown", ROOT));
            }
        }
        var row = row("", parentId, root, input, problems);
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        var id = (parentId != null ? parentId : root) + "." + slug(row.nameEn());
        if (store.category(id).isPresent()) {
            throw new Conflict("category_exists", CATEGORY_EXISTS);
        }
        store.insert(withId(row, id), actorId, clock.instant());
        return store.category(id).orElseThrow();
    }

    @Override
    public Change<Category> update(String id, CategoryInput input, String actorId) {
        var before = store.category(id).orElseThrow(() -> new NotFound("category", id));
        var problems = new ArrayList<Violation>();
        var row = row(id, before.parentId(), before.root(), input, problems);
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        store.update(row, actorId, clock.instant());
        return new Change<>(before, store.category(id).orElseThrow());
    }

    @Override
    public Change<Category> regulate(String id, String province, @Nullable String regulator, String actorId) {
        var before = store.category(id).orElseThrow(() -> new NotFound("category", id));
        if (regions.province(province).isEmpty()) {
            throw RuleViolation.of("province", "unknown", PROVINCE);
        }
        var code = blank(regulator);
        if (code != null && !NONE.equals(code)) {
            var found = store.regulator(code)
                    .orElseThrow(() -> RuleViolation.of("regulator", "unknown", REGULATOR_UNKNOWN));
            if (!found.province().equals(province)) {
                throw RuleViolation.of("regulator", "province", REGULATOR_ELSEWHERE);
            }
        }
        store.regulate(id, province, code == null, NONE.equals(code) ? null : code, actorId, clock.instant());
        return new Change<>(before, store.category(id).orElseThrow());
    }

    @Override
    public Change<Category> classify(String id, @Nullable String ageClass, String actorId) {
        var before = store.category(id).orElseThrow(() -> new NotFound("category", id));
        var code = blank(ageClass);
        if (code != null && AgeClass.of(code).isEmpty()) {
            throw RuleViolation.of("ageClass", "required", AGE_CLASS);
        }
        store.classify(id, code, actorId, clock.instant());
        return new Change<>(before, store.category(id).orElseThrow());
    }

    @Override
    public Regulator createRegulator(RegulatorInput input, String actorId) {
        var problems = new ArrayList<Violation>();
        var code = input.code() == null ? "" : input.code().strip();
        if (!CODE.matcher(code).matches()) {
            problems.add(new Violation("code", "pattern", REGULATOR_CODE));
        }
        var regulator = regulator(code, input, problems);
        if (store.regulator(code).isPresent()) {
            throw new Conflict("regulator_exists", REGULATOR_EXISTS);
        }
        store.insertRegulator(regulator, actorId);
        return store.regulator(code).orElseThrow();
    }

    @Override
    public Change<Regulator> updateRegulator(String code, RegulatorInput input, String actorId) {
        var before = store.regulator(code).orElseThrow(() -> new NotFound("regulator", code));
        var problems = new ArrayList<Violation>();
        var after = regulator(code, input, problems);
        if (!after.province().equals(before.province())
                && store.categories().stream()
                        .flatMap(c -> c.regulators().stream())
                        .anyMatch(r -> code.equals(r.regulator()))) {
            // categories point at it in its old province: moving it would leave them pointing across provinces
            throw new Conflict("regulator_in_use", REGULATOR_ELSEWHERE);
        }
        store.updateRegulator(after, actorId);
        return new Change<>(before, store.regulator(code).orElseThrow());
    }

    private Regulator regulator(String code, RegulatorInput input, List<Violation> problems) {
        var name = input.name() == null ? "" : input.name().strip();
        if (name.isEmpty() || name.length() > 80) {
            problems.add(new Violation("name", "length", REGULATOR_NAME));
        }
        var province = input.province() == null ? "" : input.province().strip();
        if (regions.province(province).isEmpty()) {
            problems.add(new Violation("province", "unknown", PROVINCE));
        }
        var website = blank(input.website());
        if (website != null && (!website.startsWith("https://") || website.length() > 200)) {
            problems.add(new Violation("website", "pattern", WEBSITE));
        }
        if (!problems.isEmpty()) {
            throw new RuleViolation(problems);
        }
        return new Regulator(code, name, province, website, clock.instant());
    }

    private static TaxonomyStore.Row row(
            String id, @Nullable String parentId, String root, CategoryInput input, List<Violation> problems) {
        var nameEn = input.nameEn() == null ? "" : input.nameEn().strip();
        if (nameEn.isEmpty() || nameEn.length() > 80 || slug(nameEn).isEmpty()) {
            problems.add(new Violation("nameEn", "length", NAME_EN));
        }
        var nameFr = blank(input.nameFr());
        if (nameFr != null && nameFr.length() > 80) {
            problems.add(new Violation("nameFr", "length", NAME_FR));
        }
        var booking = blank(input.bookingType());
        if (booking != null && (!BOOKING_TYPES.contains(booking) || !"service".equals(root))) {
            problems.add(new Violation("bookingType", "unknown", BOOKING_TYPE));
        }
        var registry = blank(input.regulatedRegistry());
        if (registry != null && registry.length() > 80) {
            problems.add(new Violation("regulatedRegistry", "length", REGISTRY));
        }
        return new TaxonomyStore.Row(id, parentId, root, nameEn, nameFr, booking, registry, input.requiresVsCheck());
    }

    private static TaxonomyStore.Row withId(TaxonomyStore.Row r, String id) {
        return new TaxonomyStore.Row(
                id,
                r.parentId(),
                r.root(),
                r.nameEn(),
                r.nameFr(),
                r.bookingType(),
                r.regulatedRegistry(),
                r.requiresVsCheck());
    }

    /** As the seeder makes ids ({@code Mobile mechanic} → {@code mobile-mechanic}). */
    static String slug(String name) {
        return java.text.Normalizer.normalize(name, java.text.Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT)
                .replace("&", "and")
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-|-$)", "");
    }

    private static @Nullable String blank(@Nullable String s) {
        return s == null || s.isBlank() ? null : s.strip();
    }
}
