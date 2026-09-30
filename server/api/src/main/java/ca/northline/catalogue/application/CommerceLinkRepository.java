package ca.northline.catalogue.application;

import ca.northline.catalogue.domain.CommerceProvider;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * Outbound port: which Northline listing each platform product became ({@code catalogue.commerce_products} +
 * {@code commerce_variants}, S-35). Kept after a disconnect so that reconnecting re-links instead of duplicating.
 */
public interface CommerceLinkRepository {

    record ProductLink(
            String merchantId,
            CommerceProvider provider,
            String externalId,
            String offerId,
            String contentHash,
            @Nullable Instant externalUpdatedAt,
            @Nullable Instant removedAt) {}

    /** One platform variant ↔ the Northline SKU (variant, or the offer itself when there is one variant). */
    record VariantLink(
            String externalId, String sku, @Nullable String stockRef) {}

    /** Every product link of the connection, by external id. */
    Map<String, ProductLink> products(String merchantId, CommerceProvider provider);

    Optional<ProductLink> product(String merchantId, CommerceProvider provider, String externalId);

    /** The product whose variant has this inventory reference. */
    Optional<String> productOfStock(String merchantId, CommerceProvider provider, String stockRef);

    /** Upserts the product link and replaces its variant links. */
    void save(ProductLink link, List<VariantLink> variants, Instant at);

    void markRemoved(String merchantId, CommerceProvider provider, String externalId, Instant at);

    /** Shopify {@code shop/redact}: forgets every link of the connection. */
    int deleteAll(String merchantId, CommerceProvider provider);
}
