package ca.northline.catalogue.adapters;

import ca.northline.catalogue.application.CommerceSync;
import ca.northline.catalogue.application.ListingQueries;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.catalogue.domain.ListingKind;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * Local fake of Shopify / Square / Lightspeed: connecting always succeeds with a made-up account label, and the
 * "external" inventory mirrors the merchant's own products with stock + 1, so a sync visibly updates stock.
 */
@Component
@Profile({"local", "test"})
@RequiredArgsConstructor
class FakeCommerceSync implements CommerceSync {

    private final ListingQueries listings;

    @Override
    public String connect(String merchantId, CommerceProvider provider) {
        var shop = "shop-"
                + merchantId.substring(Math.max(0, merchantId.length() - 4)).toLowerCase(Locale.ROOT);
        return switch (provider) {
            case SHOPIFY -> shop + ".myshopify.com";
            case SQUARE -> "Square · " + shop;
            case LIGHTSPEED -> "Lightspeed Retail · " + shop;
        };
    }

    @Override
    public List<ExternalItem> fetchInventory(String merchantId, CommerceProvider provider) {
        return listings.list(merchantId, ListingKind.PRODUCT, 500, Locale.ENGLISH).stream()
                .filter(l -> l.sku() != null && l.priceCents() != null && l.stock() != null)
                .map(l -> new ExternalItem(
                        Objects.requireNonNull(l.sku()),
                        Objects.requireNonNull(l.priceCents()),
                        Objects.requireNonNull(l.stock()) + 1))
                .toList();
    }
}
