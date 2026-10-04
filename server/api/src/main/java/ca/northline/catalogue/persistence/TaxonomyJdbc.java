package ca.northline.catalogue.persistence;

import ca.northline.catalogue.api.TaxonomyAdmin.Category;
import ca.northline.catalogue.api.TaxonomyAdmin.ProvinceRule;
import ca.northline.catalogue.api.TaxonomyAdmin.Regulator;
import ca.northline.catalogue.application.TaxonomyStore;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.json.JsonMapper;

/** {@link TaxonomyStore} over {@code catalogue.categories} (+ the French label), regulators and province rules. */
@Repository
@RequiredArgsConstructor
class TaxonomyJdbc implements TaxonomyStore {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static final String CATEGORY = """
            select c.id, c.parent_id, c.root, c.name_i18n->>'en' as name_en,
                   coalesce(nullif(c.name_i18n->>'fr', ''), l.name) as name_fr,
                   nullif(c.booking_type, 'null') as booking_type, c.regulated_registry,
                   coalesce(c.requires_vs_check, false) as requires_vs_check, c.edited_at,
                   (select a.age_class from catalogue.category_age_classes a where a.category_id = c.id) as age_class
              from catalogue.categories c
              left join catalogue.category_labels l on l.category_id = c.id and l.lang = 'fr'""";

    /** Live listings by category and pricing mode: services, and goods by their catalogue product's category. */
    private static final String PRICES = """
            with listings as (
              select category_id, coalesce(pricing_mode, 'fixed') as mode, price_cents
                from catalogue.services where status = 'live' and category_id is not null
              union all
              select p.category_id, 'fixed', o.price_cents
                from catalogue.offers o join catalogue.catalog_products p on p.id = o.product_id
               where o.status = 'live' and p.category_id is not null)
            select category_id, mode, count(*) as n,
                   percentile_cont(0.5) within group (order by price_cents) as median
              from listings group by category_id, mode""";

    private final JdbcClient jdbc;

    private record Price(
            int listings,
            @Nullable Long medianCents,
            @Nullable String mode) {}

    @Override
    public List<Category> categories() {
        var rules = rules(null);
        var prices = prices();
        return jdbc.sql(CATEGORY + " order by c.root, c.parent_id nulls first, c.id")
                .query((rs, _) -> category(rs, rules, prices))
                .list();
    }

    @Override
    public Optional<Category> category(String id) {
        var rules = rules(id);
        var prices = prices();
        return jdbc.sql(CATEGORY + " where c.id = :id")
                .param("id", id)
                .query((rs, _) -> category(rs, rules, prices))
                .optional();
    }

    @Override
    public void insert(Row row, String actorId, Instant at) {
        jdbc.sql("""
                        insert into catalogue.categories (id, parent_id, root, name_i18n, booking_type, regulated_registry,
                                                          requires_vs_check, edited_at, edited_by)
                        values (:id, :parent, :root, :names::jsonb, :booking, :registry, :vs, :at, :by)""")
                .param("id", row.id())
                .param("parent", row.parentId())
                .param("root", row.root())
                .param("names", names(row))
                .param("booking", row.bookingType())
                .param("registry", row.regulatedRegistry())
                .param("vs", row.requiresVsCheck())
                .param("at", JdbcTimes.ts(at))
                .param("by", actorId)
                .update();
        label(row);
    }

    @Override
    public void update(Row row, String actorId, Instant at) {
        jdbc.sql("""
                        update catalogue.categories
                           set name_i18n = :names::jsonb, booking_type = :booking, regulated_registry = :registry,
                               requires_vs_check = :vs, edited_at = :at, edited_by = :by
                         where id = :id""")
                .param("id", row.id())
                .param("names", names(row))
                .param("booking", row.bookingType())
                .param("registry", row.regulatedRegistry())
                .param("vs", row.requiresVsCheck())
                .param("at", JdbcTimes.ts(at))
                .param("by", actorId)
                .update();
        label(row);
    }

    @Override
    public void regulate(
            String categoryId, String province, boolean clear, @Nullable String regulator, String actorId, Instant at) {
        if (clear) {
            jdbc.sql("delete from catalogue.category_regulators where category_id = :id and province = :p")
                    .param("id", categoryId)
                    .param("p", province)
                    .update();
        } else {
            jdbc.sql("""
                            insert into catalogue.category_regulators (category_id, province, regulator, updated_by, updated_at)
                            values (:id, :p, :r, :by, :at)
                            on conflict (category_id, province) do update
                               set regulator = excluded.regulator, updated_by = excluded.updated_by,
                                   updated_at = excluded.updated_at""")
                    .param("id", categoryId)
                    .param("p", province)
                    .param("r", regulator)
                    .param("by", actorId)
                    .param("at", JdbcTimes.ts(at))
                    .update();
        }
        jdbc.sql("update catalogue.categories set edited_at = :at, edited_by = :by where id = :id")
                .param("id", categoryId)
                .param("at", JdbcTimes.ts(at))
                .param("by", actorId)
                .update();
    }

    @Override
    public List<Regulator> regulators() {
        return jdbc.sql("select * from catalogue.regulators order by province, name")
                .query((rs, _) -> regulator(rs))
                .list();
    }

    @Override
    public Optional<Regulator> regulator(String code) {
        return jdbc.sql("select * from catalogue.regulators where code = :code")
                .param("code", code)
                .query((rs, _) -> regulator(rs))
                .optional();
    }

    @Override
    public void insertRegulator(Regulator r, String actorId) {
        jdbc.sql("""
                        insert into catalogue.regulators (code, name, province, website, updated_by, updated_at)
                        values (:code, :name, :province, :website, :by, :at)""")
                .param("code", r.code())
                .param("name", r.name())
                .param("province", r.province())
                .param("website", r.website())
                .param("by", actorId)
                .param("at", JdbcTimes.ts(r.updatedAt()))
                .update();
    }

    @Override
    public void updateRegulator(Regulator r, String actorId) {
        jdbc.sql("""
                        update catalogue.regulators
                           set name = :name, province = :province, website = :website, updated_by = :by, updated_at = :at
                         where code = :code""")
                .param("code", r.code())
                .param("name", r.name())
                .param("province", r.province())
                .param("website", r.website())
                .param("by", actorId)
                .param("at", JdbcTimes.ts(r.updatedAt()))
                .update();
    }

    /** French goes to {@code name_i18n} and to {@code category_labels} (the shop reads the label, V111). */
    private void label(Row row) {
        if (row.nameFr() == null) {
            jdbc.sql("delete from catalogue.category_labels where category_id = :id and lang = 'fr'")
                    .param("id", row.id())
                    .update();
            return;
        }
        jdbc.sql("""
                        insert into catalogue.category_labels (category_id, lang, name) values (:id, 'fr', :name)
                        on conflict (category_id, lang) do update set name = excluded.name""").param("id", row.id()).param("name", row.nameFr()).update();
    }

    private static String names(Row row) {
        var names = new java.util.LinkedHashMap<String, String>();
        names.put("en", row.nameEn());
        if (row.nameFr() != null) {
            names.put("fr", row.nameFr());
        }
        return JSON.writeValueAsString(names);
    }

    private Map<String, List<ProvinceRule>> rules(@Nullable String categoryId) {
        var out = new HashMap<String, List<ProvinceRule>>();
        jdbc.sql("select category_id, province, regulator from catalogue.category_regulators"
                        + (categoryId == null ? "" : " where category_id = :id")
                        + " order by province")
                .params(categoryId == null ? Map.of() : Map.of("id", categoryId))
                .query(rs -> {
                    out.computeIfAbsent(rs.getString("category_id"), _ -> new ArrayList<>())
                            .add(new ProvinceRule(rs.getString("province"), rs.getString("regulator")));
                });
        return out;
    }

    private Map<String, Price> prices() {
        record Mode(String mode, int n, @Nullable Long median) {}
        var byCategory = new HashMap<String, List<Mode>>();
        jdbc.sql(PRICES).query(rs -> {
            var median = rs.getObject("median") == null ? null : Math.round(rs.getDouble("median"));
            byCategory
                    .computeIfAbsent(rs.getString("category_id"), _ -> new ArrayList<>())
                    .add(new Mode(rs.getString("mode"), rs.getInt("n"), median));
        });
        var out = new HashMap<String, Price>();
        byCategory.forEach((id, modes) -> {
            var total = modes.stream().mapToInt(Mode::n).sum();
            var priced = modes.stream()
                    .filter(m -> !"quote".equals(m.mode()))
                    .max(java.util.Comparator.comparingInt(Mode::n));
            out.put(
                    id,
                    priced.map(m -> new Price(total, m.median(), m.mode())).orElse(new Price(total, null, "quote")));
        });
        return out;
    }

    private static Category category(ResultSet rs, Map<String, List<ProvinceRule>> rules, Map<String, Price> prices)
            throws SQLException {
        var id = rs.getString("id");
        var price = prices.get(id);
        var name = rs.getString("name_en");
        return new Category(
                id,
                rs.getString("parent_id"),
                rs.getString("root"),
                name == null ? id : name,
                rs.getString("name_fr"),
                rs.getString("booking_type"),
                rs.getString("regulated_registry"),
                rs.getBoolean("requires_vs_check"),
                rules.getOrDefault(id, List.of()),
                price == null ? 0 : price.listings(),
                price == null ? null : price.medianCents(),
                price == null ? null : price.mode(),
                JdbcTimes.instant(rs, "edited_at"),
                rs.getString("age_class"));
    }

    @Override
    public void classify(String categoryId, @Nullable String ageClass, String actorId, Instant at) {
        if (ageClass == null) {
            jdbc.sql("delete from catalogue.category_age_classes where category_id = :id")
                    .param("id", categoryId)
                    .update();
            return;
        }
        jdbc.sql("""
                        insert into catalogue.category_age_classes (category_id, age_class, updated_by, updated_at)
                        values (:id, :c, :by, :at)
                        on conflict (category_id) do update set age_class = excluded.age_class,
                          updated_by = excluded.updated_by, updated_at = excluded.updated_at
                        """)
                .param("id", categoryId)
                .param("c", ageClass)
                .param("by", actorId)
                .param("at", JdbcTimes.ts(at))
                .update();
    }

    private static Regulator regulator(ResultSet rs) throws SQLException {
        return new Regulator(
                rs.getString("code"),
                rs.getString("name"),
                rs.getString("province"),
                rs.getString("website"),
                JdbcTimes.requiredInstant(rs, "updated_at"));
    }
}
