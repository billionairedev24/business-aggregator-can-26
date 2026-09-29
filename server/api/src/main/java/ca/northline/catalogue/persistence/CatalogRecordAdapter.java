package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.*;

import ca.northline.catalogue.application.CatalogRecords;
import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.catalog_products}; {@code sellerCount} counts the offers attached to each record. */
@Repository
@RequiredArgsConstructor
class CatalogRecordAdapter implements CatalogRecords {

    private static final String SELECT = """
            select cp.*, (select count(*) from catalogue.offers o where o.product_id = cp.id) as seller_count
              from catalogue.catalog_products cp
            """;

    private final JdbcClient jdbc;

    @Override
    public Optional<CatalogRecord> byGtin(String gtin) {
        return jdbc.sql(SELECT + " where cp.gtin = :gtin")
                .param("gtin", gtin)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public Optional<CatalogRecord> byId(String id) {
        return jdbc.sql(SELECT + " where cp.id = :id")
                .param("id", id)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public String nextRef() {
        var n = jdbc.sql("select nextval('catalogue.catalog_product_ref_seq')")
                .query(Long.class)
                .single();
        return "NL-P-" + n;
    }

    @Override
    public void insert(CatalogRecord r) {
        jdbc.sql("""
                        insert into catalogue.catalog_products (id, ref, gtin, identifier_type, brand, title, title_i18n, mpn,
                          category_id, attributes, description, bullets, image_set, owner_merchant_id, created_by_merchant_id,
                          locked)
                        values (:id, :ref, :gtin, :type, :brand, :title, cast(:titleI18n as jsonb), :mpn, :category,
                          cast(:attributes as jsonb), :description, :bullets, :images, :owner, :createdBy, :locked)
                        """).params(params(r)).update();
    }

    @Override
    public void update(CatalogRecord r) {
        jdbc.sql("""
                        update catalogue.catalog_products set gtin = :gtin, identifier_type = :type, brand = :brand,
                          title = :title, title_i18n = coalesce(title_i18n, '{}'::jsonb) || cast(:titleI18n as jsonb),
                          mpn = :mpn, category_id = :category, attributes = cast(:attributes as jsonb),
                          description = :description, bullets = :bullets, image_set = :images, updated_at = now()
                         where id = :id
                        """).params(params(r)).update();
    }

    private static Map<String, @Nullable Object> params(CatalogRecord r) {
        var p = new java.util.HashMap<String, @Nullable Object>();
        p.put("id", r.id());
        p.put("ref", r.ref());
        p.put("gtin", r.gtin());
        p.put("type", r.identifierType().code());
        p.put("brand", r.brand());
        p.put("title", r.title());
        p.put("titleI18n", json(Map.of("en", r.title())));
        p.put("mpn", r.mpn());
        p.put("category", r.categoryId());
        p.put("attributes", json(r.attributes()));
        p.put("description", r.description());
        p.put("bullets", array(r.bullets()));
        p.put("images", array(r.imageIds()));
        p.put("owner", r.ownerMerchantId());
        p.put("createdBy", r.createdByMerchantId());
        p.put("locked", r.locked());
        return p;
    }

    static CatalogRecord map(ResultSet rs) throws SQLException {
        return new CatalogRecord(
                rs.getString("id"),
                Objects.requireNonNullElse(rs.getString("ref"), ""),
                rs.getString("gtin"),
                CodedEnum.fromCode(IdentifierType.class, rs.getString("identifier_type")),
                rs.getString("brand"),
                Objects.requireNonNullElse(rs.getString("title"), ""),
                rs.getString("mpn"),
                rs.getString("category_id"),
                stringMap(rs.getString("attributes")),
                rs.getString("description"),
                strings(rs, "bullets"),
                strings(rs, "image_set"),
                rs.getString("owner_merchant_id"),
                rs.getString("created_by_merchant_id"),
                rs.getBoolean("locked"),
                rs.getInt("seller_count"));
    }
}
