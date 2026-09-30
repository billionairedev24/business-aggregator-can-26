package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.*;

import ca.northline.catalogue.application.ListingQueries;
import ca.northline.catalogue.application.ListingSummary;
import ca.northline.catalogue.domain.ListingKind;
import ca.northline.catalogue.domain.ListingStatus;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.catalogue.domain.VettingFlag;
import ca.northline.shared.CodedEnum;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** The listings table: services ∪ offers of one merchant, services first, then oldest first (design order). */
@Repository
@RequiredArgsConstructor
class ListingQueriesAdapter implements ListingQueries {

    private final JdbcClient jdbc;

    @Override
    public List<ListingSummary> list(String merchantId, @Nullable ListingKind kind, int limit, Locale locale) {
        return jdbc.sql("""
                        select * from (
                          select s.id, 'service' as kind, s.name as name, s.sku, s.price_cents, null::integer as stock,
                                 s.sales_30d, s.vetting, s.status, s.vetting_flags, s.revet_reasons, s.submitted_at, s.category_id,
                                 coalesce(c.name_i18n ->> :lang, c.name_i18n ->> 'en') as category_name, s.pricing_mode,
                                 s.duration_min, coalesce(s.instant_book, false) as instant_book, s.created_at, s.updated_at
                            from catalogue.services s
                            left join catalogue.categories c on c.id = s.category_id
                           where s.merchant_id = :m
                          union all
                          select o.id, 'product', o.title, o.sku, o.price_cents, o.stock, o.sales_30d, o.vetting, o.status,
                                 o.vetting_flags, o.revet_reasons, o.submitted_at, cp.category_id,
                                 coalesce(c.name_i18n ->> :lang, c.name_i18n ->> 'en'), null, null, false, o.created_at,
                                 o.updated_at
                            from catalogue.offers o
                            join catalogue.catalog_products cp on cp.id = o.product_id
                            left join catalogue.categories c on c.id = cp.category_id
                           where o.merchant_id = :m
                        ) l
                         where cast(:kind as text) is null or l.kind = cast(:kind as text)
                         order by l.kind = 'product', l.created_at, l.id
                         limit :limit
                        """)
                .param("m", merchantId)
                .param("lang", locale.getLanguage())
                .param("kind", kind == null ? null : kind.code())
                .param("limit", limit)
                .query((rs, _) -> new ListingSummary(
                        rs.getString("id"),
                        CodedEnum.fromCode(ListingKind.class, rs.getString("kind")),
                        Objects.requireNonNullElse(rs.getString("name"), ""),
                        rs.getString("sku"),
                        longOrNull(rs, "price_cents"),
                        intOrNull(rs, "stock"),
                        rs.getInt("sales_30d"),
                        Objects.requireNonNullElse(enumOrNull(rs, "vetting", Vetting.class), Vetting.DRAFT),
                        Objects.requireNonNullElse(enumOrNull(rs, "status", ListingStatus.class), ListingStatus.HIDDEN),
                        enums(rs, "vetting_flags", VettingFlag.class),
                        enums(rs, "revet_reasons", MaterialField.class),
                        instant(rs, "submitted_at"),
                        rs.getString("category_id"),
                        rs.getString("category_name"),
                        enumOrNull(rs, "pricing_mode", PricingMode.class),
                        intOrNull(rs, "duration_min"),
                        rs.getBoolean("instant_book"),
                        requiredInstant(rs, "updated_at")))
                .list();
    }

    @Override
    public int count(String merchantId) {
        return jdbc.sql("""
                        select (select count(*) from catalogue.offers where merchant_id = :m)
                             + (select count(*) from catalogue.services where merchant_id = :m)
                        """).param("m", merchantId).query(Integer.class).single();
    }
}
