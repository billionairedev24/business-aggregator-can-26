package ca.northline.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.catalogue.application.CommerceCatalogSource.ExternalProduct;
import ca.northline.catalogue.application.CommerceCatalogSource.ExternalVariant;
import ca.northline.catalogue.domain.CommerceProvider;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ProductDetails.PriceStock;
import ca.northline.catalogue.domain.VariantTheme;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** S-35 mapping from a platform product to the editor's details, and the price & stock sync on a listing. */
class CommerceImporterTest {

    static ExternalVariant variant(String id, String sku, Map<String, String> options, long price, int stock) {
        return new ExternalVariant(id, sku, null, id, options, price, stock, "inv-" + id);
    }

    @Test
    void aFamilyBecomesOneDraftWithVariants() {
        var p = new ExternalProduct(
                "gid://shopify/Product/1",
                "  Work   jacket ",
                "<p>Warm.</p><ul><li>Pockets &amp; hood</li></ul>",
                "Acme",
                List.of(),
                List.of(
                        variant("a", "J-M-RED", Map.of("Size", "M", "Color", "Red"), 12900, 2),
                        variant("b", null, Map.of("Size", "L", "Color", "Red"), 13900, -1),
                        variant("c", "J-M-RED", Map.of("Size", "M", "Color", "Red"), 12900, 1)),
                null);
        var d = CommerceImporter.details(CommerceProvider.SHOPIFY, p, null, List.of());
        assertThat(d.title()).isEqualTo("Work jacket");
        assertThat(d.brand()).isEqualTo("Acme");
        assertThat(d.description()).isEqualTo("Warm.\nPockets & hood");
        assertThat(d.variantTheme()).isEqualTo(VariantTheme.SIZE_COLOUR);
        assertThat(d.variants()).extracting(v -> v.sku()).containsExactly("J-M-RED", "SHO-b", "J-M-RED-2");
        assertThat(d.variants()).extracting(v -> v.value()).doesNotHaveDuplicates();
        assertThat(d.priceCents()).isEqualTo(12900);
        assertThat(d.stock()).isEqualTo(3); // negative platform stock counts as 0
        assertThat(d.identifierType()).isEqualTo(IdentifierType.NONE);
        assertThat(d.validate(null)).isEmpty();
    }

    @Test
    void aSingleProductKeepsItsSkuAndAValidBarcode() {
        var p = new ExternalProduct(
                "1",
                "Oil filter",
                null,
                null,
                List.of(),
                List.of(new ExternalVariant("1", "OF-1", "028851152280", "Default", Map.of(), 999, 4, null)),
                null);
        var d = CommerceImporter.details(CommerceProvider.SQUARE, p, null, List.of());
        assertThat(d.sku()).isEqualTo("OF-1");
        assertThat(d.gtin()).isEqualTo("028851152280");
        assertThat(d.variantTheme()).isEqualTo(VariantTheme.NONE);
        var invalid = new ExternalProduct(
                "1",
                "Oil filter",
                null,
                null,
                List.of(),
                List.of(new ExternalVariant("1", "OF-1", "12345", "Default", Map.of(), 999, 4, null)),
                null);
        assertThat(CommerceImporter.details(CommerceProvider.SQUARE, invalid, null, List.of())
                        .identifierType())
                .isEqualTo(IdentifierType.NONE);
    }

    @Test
    void longTitlesAreCutAtAWordAndTheHashIgnoresPriceAndStock() {
        assertThat(CommerceImporter.title("word ".repeat(30)))
                .hasSizeLessThanOrEqualTo(80)
                .doesNotEndWith(" ");
        var a = new ExternalProduct(
                "1", "T", null, null, List.of(), List.of(variant("a", "S", Map.of(), 100, 1)), null);
        var b = new ExternalProduct(
                "1", "T", null, null, List.of(), List.of(variant("a", "S", Map.of(), 200, 9)), null);
        var c = new ExternalProduct(
                "1", "T2", null, null, List.of(), List.of(variant("a", "S", Map.of(), 100, 1)), null);
        assertThat(CommerceImporter.contentHash(a))
                .isEqualTo(CommerceImporter.contentHash(b))
                .isNotEqualTo(CommerceImporter.contentHash(c));
    }

    @Test
    void syncedStockFollowsThePlatformPerSku() {
        var family = new ExternalProduct(
                "1",
                "Mats",
                null,
                null,
                List.of(),
                List.of(
                        variant("a", "M-B", Map.of("Colour", "Black"), 8999, 2),
                        variant("b", "M-G", Map.of("Colour", "Grey"), 8999, 3)),
                null);
        var d = CommerceImporter.details(CommerceProvider.LIGHTSPEED, family, null, List.of());
        var synced = d.withSyncedStock(Map.of("M-B", new PriceStock(7999, 5)));
        assertThat(synced.variants()).extracting(v -> v.stock()).containsExactly(5, 0); // Grey is gone on the platform
        assertThat(synced.priceCents()).isEqualTo(7999);
        assertThat(synced.stock()).isEqualTo(5);
        assertThat(d.withSyncedStock(Map.of("M-B", new PriceStock(8999, 2), "M-G", new PriceStock(8999, 3))))
                .isSameAs(d);
    }
}
