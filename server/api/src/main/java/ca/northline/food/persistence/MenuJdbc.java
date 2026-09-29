package ca.northline.food.persistence;

import static ca.northline.food.persistence.KitchenSql.array;
import static ca.northline.food.persistence.KitchenSql.i18n;
import static ca.northline.food.persistence.KitchenSql.intOrNull;
import static ca.northline.food.persistence.KitchenSql.json;
import static ca.northline.food.persistence.KitchenSql.strings;
import static ca.northline.food.persistence.KitchenSql.stringsOrNull;

import ca.northline.food.application.MenuStore;
import ca.northline.food.application.MenuViews.MenuSchedule;
import ca.northline.food.domain.ItemStatus;
import ca.northline.food.domain.ItemWindow;
import ca.northline.food.domain.MenuStatus;
import ca.northline.shared.CodedEnum;
import ca.northline.shared.JdbcTimes;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code food.menus / menu_sections / menu_items / item_modifiers}. Every query is scoped by merchant. */
@Repository
@RequiredArgsConstructor
class MenuJdbc implements MenuStore {

    private static final String ITEM_COLUMNS = """
            i.id, i.merchant_id, s.menu_id, i.section_id, coalesce(i.name, i.name_i18n ->> 'en', 'Item') as name,
            i.description, coalesce(i.price_cents, 0) as price_cents, i.allergens, i.dietary,
            coalesce(i.prep_add_min, 0) as prep_add_min, i.daily_limit, coalesce(i.sold_today, 0) as sold_today,
            i.sold_out_on, i.availability, i.combo_eligible, i.status, coalesce(i.vetting, 'draft') as vetting,
            coalesce(i.available, true) as available, i.photo_key, i.photo_content_type, i.sort, i.published_at,
            i.updated_at,
            array(select m.group_id from food.item_modifiers m where m.item_id = i.id order by m.sort, m.group_id)
              as group_ids
            """;
    private static final String ITEM_FROM = """
             from food.menu_items i
             join food.menu_sections s on s.id = i.section_id
             join food.menus mn on mn.id = s.menu_id
            """;

    private final JdbcClient jdbc;

    @Override
    public List<MenuRow> menus(String merchantId) {
        return jdbc.sql("select * from food.menus where merchant_id = :m order by sort, created_at, id")
                .param("m", merchantId)
                .query((rs, _) -> menu(rs))
                .list();
    }

    @Override
    public Optional<MenuRow> menu(String merchantId, String menuId) {
        return jdbc.sql("select * from food.menus where merchant_id = :m and id = :id")
                .param("m", merchantId)
                .param("id", menuId)
                .query((rs, _) -> menu(rs))
                .optional();
    }

    @Override
    public List<SectionRow> sections(String merchantId, @Nullable String menuId) {
        return jdbc.sql("""
                        select s.id, s.menu_id, coalesce(s.name, s.name_i18n ->> 'en', 'Section') as name,
                               coalesce(s.sort, 0) as sort
                          from food.menu_sections s join food.menus mn on mn.id = s.menu_id
                         where mn.merchant_id = :m and (cast(:menu as text) is null or s.menu_id = :menu)
                         order by mn.sort, mn.id, s.sort, s.id
                        """)
                .param("m", merchantId)
                .param("menu", menuId, java.sql.Types.VARCHAR)
                .query((rs, _) -> new SectionRow(
                        rs.getString("id"), rs.getString("menu_id"), rs.getString("name"), rs.getInt("sort")))
                .list();
    }

    @Override
    public List<ItemRow> items(String merchantId, @Nullable String menuId) {
        return jdbc.sql("select " + ITEM_COLUMNS + ITEM_FROM + """
                         where i.merchant_id = :m and mn.merchant_id = :m
                           and (cast(:menu as text) is null or s.menu_id = :menu)
                         order by mn.sort, s.sort, i.sort, i.id
                        """)
                .param("m", merchantId)
                .param("menu", menuId, java.sql.Types.VARCHAR)
                .query((rs, _) -> item(rs))
                .list();
    }

    @Override
    public Optional<ItemRow> item(String merchantId, String itemId) {
        return jdbc.sql("select " + ITEM_COLUMNS + ITEM_FROM + " where i.merchant_id = :m and i.id = :id")
                .param("m", merchantId)
                .param("id", itemId)
                .query((rs, _) -> item(rs))
                .optional();
    }

    @Override
    public List<ItemRow> publishedItems(String merchantId) {
        return jdbc.sql("select " + ITEM_COLUMNS + ITEM_FROM + " where i.merchant_id = :m and i.status = 'published'")
                .param("m", merchantId)
                .query((rs, _) -> item(rs))
                .list();
    }

    @Override
    public void insertMenu(MenuRow m) {
        jdbc.sql("""
                        insert into food.menus (id, merchant_id, name, name_i18n, schedule, status, sort, published_at)
                        values (:id, :m, :name, cast(:i18n as jsonb), cast(:schedule as jsonb), :status, :sort, :published)
                        """)
                .param("id", m.id())
                .param("m", m.merchantId())
                .param("name", m.name())
                .param("i18n", i18n(m.name()))
                .param("schedule", json(m.schedule()))
                .param("status", m.status().code())
                .param("sort", m.sort())
                .param("published", JdbcTimes.ts(m.publishedAt()))
                .update();
    }

    @Override
    public void updateMenu(MenuRow m) {
        jdbc.sql("""
                        update food.menus set name = :name, name_i18n = cast(:i18n as jsonb), schedule = cast(:schedule as jsonb),
                               status = :status, sort = :sort, published_at = :published, updated_at = now()
                         where id = :id and merchant_id = :m
                        """)
                .param("id", m.id())
                .param("m", m.merchantId())
                .param("name", m.name())
                .param("i18n", i18n(m.name()))
                .param("schedule", json(m.schedule()))
                .param("status", m.status().code())
                .param("sort", m.sort())
                .param("published", JdbcTimes.ts(m.publishedAt()))
                .update();
    }

    @Override
    public void insertSection(SectionRow s) {
        jdbc.sql("""
                        insert into food.menu_sections (id, menu_id, name, name_i18n, sort)
                        values (:id, :menu, :name, cast(:i18n as jsonb), :sort)
                        """)
                .param("id", s.id())
                .param("menu", s.menuId())
                .param("name", s.name())
                .param("i18n", i18n(s.name()))
                .param("sort", s.sort())
                .update();
    }

    @Override
    public void updateSection(SectionRow s) {
        jdbc.sql("""
                        update food.menu_sections set name = :name, name_i18n = cast(:i18n as jsonb), sort = :sort
                         where id = :id and menu_id = :menu
                        """)
                .param("id", s.id())
                .param("menu", s.menuId())
                .param("name", s.name())
                .param("i18n", i18n(s.name()))
                .param("sort", s.sort())
                .update();
    }

    @Override
    public void reorderSections(String menuId, List<String> sectionIds) {
        for (int i = 0; i < sectionIds.size(); i++) {
            jdbc.sql("update food.menu_sections set sort = :sort where id = :id and menu_id = :menu")
                    .param("sort", i)
                    .param("id", sectionIds.get(i))
                    .param("menu", menuId)
                    .update();
        }
    }

    @Override
    public void insertItem(ItemRow i) {
        jdbc.sql("""
                        insert into food.menu_items (id, section_id, merchant_id, name, name_i18n, description, desc_i18n,
                               price_cents, allergens, dietary, prep_add_min, daily_limit, sold_today, sold_out_on,
                               available, vetting, availability, combo_eligible, status, photo_key, photo_content_type,
                               sort, published_at, updated_at)
                        values (:id, :section, :m, :name, cast(:nameI18n as jsonb), :description, cast(:descI18n as jsonb),
                               :price, case when :declared then cast(:allergens as text[]) end, cast(:dietary as text[]),
                               :prep, :limit, :soldToday, :soldOutOn, :available, :vetting, :availability, :comboEligible,
                               :status, :photoKey, :photoType, :sort, :publishedAt, now())
                        """).params(params(i)).update();
        replaceGroups(i);
    }

    @Override
    public void updateItem(ItemRow i) {
        jdbc.sql("""
                        update food.menu_items set section_id = :section, name = :name, name_i18n = cast(:nameI18n as jsonb),
                               description = :description, desc_i18n = cast(:descI18n as jsonb), price_cents = :price,
                               allergens = case when :declared then cast(:allergens as text[]) end,
                               dietary = cast(:dietary as text[]), prep_add_min = :prep, daily_limit = :limit,
                               sold_today = :soldToday, sold_out_on = :soldOutOn, available = :available,
                               vetting = :vetting, availability = :availability, combo_eligible = :comboEligible,
                               status = :status, photo_key = :photoKey, photo_content_type = :photoType, sort = :sort,
                               published_at = :publishedAt, updated_at = now()
                         where id = :id and merchant_id = :m
                        """).params(params(i)).update();
        replaceGroups(i);
    }

    @Override
    public void deleteItem(String merchantId, String itemId) {
        jdbc.sql("delete from food.item_modifiers where item_id = :id")
                .param("id", itemId)
                .update();
        jdbc.sql("delete from food.menu_items where id = :id and merchant_id = :m")
                .param("id", itemId)
                .param("m", merchantId)
                .update();
    }

    private void replaceGroups(ItemRow i) {
        jdbc.sql("delete from food.item_modifiers where item_id = :id")
                .param("id", i.id())
                .update();
        var groups = i.modifierGroupIds();
        for (int n = 0; n < groups.size(); n++) {
            jdbc.sql("insert into food.item_modifiers (item_id, group_id, sort) values (:item, :group, :sort)")
                    .param("item", i.id())
                    .param("group", groups.get(n))
                    .param("sort", n)
                    .update();
        }
    }

    private static java.util.Map<String, @Nullable Object> params(ItemRow i) {
        var p = new java.util.HashMap<String, @Nullable Object>();
        p.put("id", i.id());
        p.put("section", i.sectionId());
        p.put("m", i.merchantId());
        p.put("name", i.name());
        p.put("nameI18n", i18n(i.name()));
        p.put("description", i.description());
        p.put("descI18n", i.description() == null ? null : i18n(i.description()));
        p.put("price", i.priceCents());
        p.put("declared", i.allergens() != null);
        p.put("allergens", array(Objects.requireNonNullElse(i.allergens(), List.of())));
        p.put("dietary", array(i.dietary()));
        p.put("prep", i.prepAddMin());
        p.put("limit", i.dailyLimit());
        p.put("soldToday", i.soldToday());
        p.put("soldOutOn", i.soldOutOn());
        p.put("available", i.available());
        p.put("vetting", i.vetting());
        p.put("availability", i.availability().code());
        p.put("comboEligible", i.comboEligible());
        p.put("status", i.status().code());
        p.put("photoKey", i.photoKey());
        p.put("photoType", i.photoContentType());
        p.put("sort", i.sort());
        p.put("publishedAt", JdbcTimes.ts(i.publishedAt()));
        return p;
    }

    private static MenuRow menu(ResultSet rs) throws SQLException {
        var schedule = rs.getString("schedule");
        return new MenuRow(
                rs.getString("id"),
                rs.getString("merchant_id"),
                Objects.requireNonNullElse(rs.getString("name"), "Menu"),
                CodedEnum.fromCode(MenuStatus.class, Objects.requireNonNullElse(rs.getString("status"), "draft")),
                schedule == null || schedule.isBlank() || schedule.equals("{}")
                        ? MenuSchedule.OPEN_HOURS
                        : KitchenSql.read(schedule, MenuSchedule.class),
                rs.getInt("sort"),
                JdbcTimes.instant(rs, "published_at"));
    }

    private static ItemRow item(ResultSet rs) throws SQLException {
        return ItemRow.builder()
                .id(rs.getString("id"))
                .merchantId(rs.getString("merchant_id"))
                .menuId(rs.getString("menu_id"))
                .sectionId(rs.getString("section_id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .priceCents(rs.getLong("price_cents"))
                .allergens(stringsOrNull(rs, "allergens"))
                .dietary(strings(rs, "dietary"))
                .prepAddMin(rs.getInt("prep_add_min"))
                .dailyLimit(intOrNull(rs, "daily_limit"))
                .soldToday(rs.getInt("sold_today"))
                .soldOutOn(KitchenSql.date(rs, "sold_out_on"))
                .availability(CodedEnum.fromCode(ItemWindow.class, rs.getString("availability")))
                .comboEligible(rs.getBoolean("combo_eligible"))
                .status(CodedEnum.fromCode(ItemStatus.class, rs.getString("status")))
                .vetting(rs.getString("vetting"))
                .available(rs.getBoolean("available"))
                .photoKey(rs.getString("photo_key"))
                .photoContentType(rs.getString("photo_content_type"))
                .sort(rs.getInt("sort"))
                .modifierGroupIds(strings(rs, "group_ids"))
                .publishedAt(JdbcTimes.instant(rs, "published_at"))
                .updatedAt(JdbcTimes.instant(rs, "updated_at"))
                .build();
    }
}
