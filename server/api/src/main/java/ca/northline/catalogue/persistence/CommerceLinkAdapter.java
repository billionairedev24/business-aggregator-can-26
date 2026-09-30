package ca.northline.catalogue.persistence;

import static ca.northline.catalogue.persistence.Sql.instant;
import static ca.northline.catalogue.persistence.Sql.ts;

import ca.northline.catalogue.application.CommerceLinkRepository;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.shared.CodedEnum;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** {@code catalogue.commerce_products} + {@code commerce_variants} (S-35). */
@Repository
@RequiredArgsConstructor
class CommerceLinkAdapter implements CommerceLinkRepository {

    private final JdbcClient jdbc;

    @Override
    public Map<String, ProductLink> products(String merchantId, CommerceProvider provider) {
        return jdbc
                .sql("select * from catalogue.commerce_products where merchant_id = :m and provider = :p")
                .param("m", merchantId)
                .param("p", provider.code())
                .query((rs, _) -> map(rs))
                .list()
                .stream()
                .collect(Collectors.toMap(ProductLink::externalId, Function.identity()));
    }

    @Override
    public Optional<ProductLink> product(String merchantId, CommerceProvider provider, String externalId) {
        return jdbc.sql("""
                        select * from catalogue.commerce_products
                        where merchant_id = :m and provider = :p and external_id = :e""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("e", externalId)
                .query((rs, _) -> map(rs))
                .optional();
    }

    @Override
    public Optional<String> productOfStock(String merchantId, CommerceProvider provider, String stockRef) {
        return jdbc.sql("""
                        select product_external_id from catalogue.commerce_variants
                        where merchant_id = :m and provider = :p and stock_ref = :s limit 1""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("s", stockRef)
                .query(String.class)
                .optional();
    }

    @Override
    public void save(ProductLink link, List<VariantLink> variants, Instant at) {
        jdbc.sql("""
                        insert into catalogue.commerce_products (merchant_id, provider, external_id, offer_id,
                          content_hash, external_updated_at, removed_at, created_at, updated_at)
                        values (:m, :p, :e, :offer, :hash, :updated, null, :at, :at)
                        on conflict (merchant_id, provider, external_id) do update set offer_id = excluded.offer_id,
                          content_hash = excluded.content_hash, external_updated_at = excluded.external_updated_at,
                          removed_at = null, updated_at = excluded.updated_at""")
                .param("m", link.merchantId())
                .param("p", link.provider().code())
                .param("e", link.externalId())
                .param("offer", link.offerId())
                .param("hash", link.contentHash())
                .param("updated", ts(link.externalUpdatedAt()))
                .param("at", ts(at))
                .update();
        jdbc.sql("""
                        delete from catalogue.commerce_variants
                        where merchant_id = :m and provider = :p and product_external_id = :e""")
                .param("m", link.merchantId())
                .param("p", link.provider().code())
                .param("e", link.externalId())
                .update();
        for (var v : variants) {
            jdbc.sql("""
                            insert into catalogue.commerce_variants (merchant_id, provider, external_id,
                              product_external_id, offer_id, sku, stock_ref)
                            values (:m, :p, :v, :e, :offer, :sku, :ref)
                            on conflict (merchant_id, provider, external_id) do update set
                              product_external_id = excluded.product_external_id, offer_id = excluded.offer_id,
                              sku = excluded.sku, stock_ref = excluded.stock_ref""")
                    .param("m", link.merchantId())
                    .param("p", link.provider().code())
                    .param("v", v.externalId())
                    .param("e", link.externalId())
                    .param("offer", link.offerId())
                    .param("sku", v.sku())
                    .param("ref", v.stockRef())
                    .update();
        }
    }

    @Override
    public void markRemoved(String merchantId, CommerceProvider provider, String externalId, Instant at) {
        jdbc.sql("""
                        update catalogue.commerce_products set removed_at = :at, updated_at = :at
                        where merchant_id = :m and provider = :p and external_id = :e and removed_at is null""")
                .param("m", merchantId)
                .param("p", provider.code())
                .param("e", externalId)
                .param("at", ts(at))
                .update();
    }

    @Override
    public int deleteAll(String merchantId, CommerceProvider provider) {
        jdbc.sql("delete from catalogue.commerce_variants where merchant_id = :m and provider = :p")
                .param("m", merchantId)
                .param("p", provider.code())
                .update();
        return jdbc.sql("delete from catalogue.commerce_products where merchant_id = :m and provider = :p")
                .param("m", merchantId)
                .param("p", provider.code())
                .update();
    }

    private static ProductLink map(ResultSet rs) throws SQLException {
        return new ProductLink(
                rs.getString("merchant_id"),
                CodedEnum.fromCode(CommerceProvider.class, rs.getString("provider")),
                rs.getString("external_id"),
                rs.getString("offer_id"),
                rs.getString("content_hash"),
                instant(rs, "external_updated_at"),
                instant(rs, "removed_at"));
    }
}
