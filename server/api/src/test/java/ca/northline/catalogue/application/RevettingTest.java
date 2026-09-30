package ca.northline.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.catalogue.api.ListingHidden;
import ca.northline.catalogue.api.ListingPublished;
import ca.northline.catalogue.api.ListingSubmitted;
import ca.northline.catalogue.domain.CatalogRecord;
import ca.northline.catalogue.domain.Fulfilment;
import ca.northline.catalogue.domain.IdentifierType;
import ca.northline.catalogue.domain.ImageSource;
import ca.northline.catalogue.domain.ItemCondition;
import ca.northline.catalogue.domain.ListingState;
import ca.northline.catalogue.domain.ListingStatus;
import ca.northline.catalogue.domain.MaterialField;
import ca.northline.catalogue.domain.PricingMode;
import ca.northline.catalogue.domain.ProductDetails;
import ca.northline.catalogue.domain.ProductDetails.PriceStock;
import ca.northline.catalogue.domain.ProductListing;
import ca.northline.catalogue.domain.ServiceDetails;
import ca.northline.catalogue.domain.ServiceListing;
import ca.northline.catalogue.domain.VariantTheme;
import ca.northline.catalogue.domain.Vetting;
import ca.northline.catalogue.domain.VettingFlag;
import ca.northline.shared.DomainEvent;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * S-39: which changes send an approved listing back to vetting, whoever makes them (editor, bulk price update,
 * platform sync), and what happens to its visibility.
 */
class RevettingTest {

    static final Instant T0 = Instant.parse("2026-09-30T16:00:00Z");
    static final Instant T1 = T0.plusSeconds(60);
    static final String ACTOR = "01J9ZD3V00000000000000RAV1";

    static final String AUTO_PARTS = "shop.hardware-and-auto.auto-parts";
    static final String TOOLS = "shop.hardware-and-auto.tools";

    static ProductDetails details(long priceCents, int stock) {
        return details("Oil filter", AUTO_PARTS, List.of("img-1", "img-2"), priceCents, stock);
    }

    static ProductDetails details(String title, String categoryId, List<String> images, long priceCents, int stock) {
        return new ProductDetails(
                IdentifierType.NONE,
                null,
                title,
                "Acme",
                null,
                categoryId,
                Map.of(),
                null,
                List.of(),
                VariantTheme.NONE,
                List.of(),
                ImageSource.OWN,
                images,
                "OF-1",
                priceCents,
                null,
                null,
                ItemCondition.NEW,
                stock,
                null,
                List.of(Fulfilment.PICKUP),
                null,
                null,
                "CA",
                true,
                true,
                false,
                null);
    }

    static CatalogRecord record() {
        return new CatalogRecord(
                "rec-1",
                "NL-P-1",
                null,
                IdentifierType.NONE,
                null,
                "Oil filter",
                null,
                "shop.hardware-and-auto.auto-parts",
                Map.of(),
                null,
                List.of(),
                List.of(),
                "M1",
                "M1",
                false,
                1);
    }

    static ListingState state(Vetting vetting, ListingStatus status) {
        return ListingState.builder()
                .vetting(vetting)
                .status(status)
                .flags(List.of())
                .submittedAt(T0)
                .createdAt(T0)
                .updatedAt(T0)
                .build();
    }

    static ProductListing product(Vetting vetting, ListingStatus status) {
        return ProductListing.builder()
                .id("L1")
                .merchantId("M1")
                .details(details(999, 4))
                .record(record())
                .state(state(vetting, status))
                .build();
    }

    static ServiceListing service(Vetting vetting, ListingStatus status) {
        return ServiceListing.builder()
                .id("S1")
                .merchantId("M1")
                .details(new ServiceDetails(
                        "Brake inspection",
                        "service.automotive.brakes-and-suspension",
                        PricingMode.FIXED,
                        8900L,
                        60,
                        20,
                        "Pads and rotors",
                        true,
                        "SVC-BI"))
                .state(state(vetting, status))
                .build();
    }

    static List<Class<?>> types(List<DomainEvent> events) {
        return events.stream().<Class<?>>map(Object::getClass).toList();
    }

    @Test
    void anEditorPriceChangeSendsALiveListingBackToVetting_andHidesIt() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        var events = listing.revise(details(1099, 4), record(), ACTOR, T1);

        assertThat(types(events)).containsExactly(ListingHidden.class, ListingSubmitted.class);
        assertThat(((ListingSubmitted) events.get(1)).actorId()).isEqualTo(ACTOR);
        var state = listing.getState();
        assertThat(state.getVetting()).isEqualTo(Vetting.PENDING);
        assertThat(state.getStatus()).isEqualTo(ListingStatus.LIVE); // the merchant's choice is kept
        assertThat(state.getSubmittedAt()).isEqualTo(T1);
        assertThat(state.getRevetReasons()).containsExactly(MaterialField.PRICE);
        assertThat(state.isCustomerVisible()).isFalse();
    }

    @Test
    void categoryAndImagesAreMaterial_includingTheOrderOfTheImages() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        var moved = details("Oil filter", TOOLS, List.of("img-1", "img-2"), 999, 4);
        assertThat(listing.revise(moved, record(), ACTOR, T1)).hasSize(2);
        assertThat(listing.getState().getRevetReasons()).containsExactly(MaterialField.CATEGORY);

        var reordered = product(Vetting.APPROVED, ListingStatus.LIVE);
        var swapped = details("Oil filter", AUTO_PARTS, List.of("img-2", "img-1"), 999, 4);
        reordered.revise(swapped, record(), ACTOR, T1);
        assertThat(reordered.getState().getRevetReasons()).containsExactly(MaterialField.IMAGES);
    }

    @Test
    void titleStockAndOtherFieldsAreNotMaterial() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        var retitled = details("Oil filter · premium", AUTO_PARTS, List.of("img-1", "img-2"), 999, 40);
        assertThat(listing.revise(retitled, record(), ACTOR, T1)).isEmpty();
        assertThat(listing.getState().getVetting()).isEqualTo(Vetting.APPROVED);
        assertThat(listing.getState().getRevetReasons()).isEmpty();
    }

    @Test
    void aPriceFromAPlatformSyncIsMaterial_stockAloneIsNot() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        var stockOnly = listing.syncStock(Map.of("OF-1", new PriceStock(999, 12)), "system:commerce", T1);
        assertThat(stockOnly).hasValue(List.of());
        assertThat(listing.getState().getVetting()).isEqualTo(Vetting.APPROVED);

        var repriced = listing.syncStock(Map.of("OF-1", new PriceStock(1299, 12)), "system:commerce", T1);
        assertThat(repriced)
                .hasValueSatisfying(
                        e -> assertThat(types(e)).containsExactly(ListingHidden.class, ListingSubmitted.class));
        assertThat(listing.getState().getRevetReasons()).containsExactly(MaterialField.PRICE);
        assertThat(listing.syncStock(Map.of("OF-1", new PriceStock(1299, 12)), "system:commerce", T1))
                .isEmpty();
    }

    @Test
    void bulkUpdatesFollowTheSameRule() {
        var product = product(Vetting.APPROVED, ListingStatus.LIVE);
        assertThat(product.restock(999, 50, ACTOR, T1)).isEmpty();
        assertThat(types(product.restock(1500, 50, ACTOR, T1)))
                .containsExactly(ListingHidden.class, ListingSubmitted.class);

        var service = service(Vetting.APPROVED, ListingStatus.LIVE);
        assertThat(service.reprice(8900L, ACTOR, T1)).isEmpty();
        assertThat(types(service.reprice(9900L, ACTOR, T1)))
                .containsExactly(ListingHidden.class, ListingSubmitted.class);
        assertThat(service.getState().getRevetReasons()).containsExactly(MaterialField.PRICE);
    }

    @Test
    void aServicesPricingModeCountsAsPrice() {
        var service = service(Vetting.APPROVED, ListingStatus.LIVE);
        var d = service.getDetails();
        var quoted = new ServiceDetails(
                d.name(),
                d.categoryId(),
                PricingMode.QUOTE,
                null,
                d.durationMin(),
                d.bufferMin(),
                d.included(),
                false,
                d.sku());
        service.revise(quoted, ACTOR, T1);
        assertThat(service.getState().getRevetReasons()).containsExactly(MaterialField.PRICE);
    }

    @Test
    void aHiddenListingIsReVettedQuietly_andStaysHiddenWhenApproved() {
        var listing = product(Vetting.APPROVED, ListingStatus.HIDDEN);
        assertThat(types(listing.revise(details(1099, 4), record(), ACTOR, T1)))
                .containsExactly(ListingSubmitted.class);

        assertThat(listing.vetted(List.of(), T1)).isEmpty();
        assertThat(listing.getState().getVetting()).isEqualTo(Vetting.APPROVED);
        assertThat(listing.getState().getStatus()).isEqualTo(ListingStatus.HIDDEN);
        assertThat(listing.getState().getRevetReasons()).isEmpty();
    }

    @Test
    void aLiveListingIsPublishedAgainOnceTheChecksPass() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        listing.revise(details(1099, 4), record(), ACTOR, T1);
        assertThat(listing.vetted(List.of(), T1)).containsInstanceOf(ListingPublished.class);
        assertThat(listing.getState().isCustomerVisible()).isTrue();
    }

    @Test
    void editsDuringReVettingKeepItPending_andMoreMaterialChangesAddUp() {
        var listing = product(Vetting.APPROVED, ListingStatus.LIVE);
        listing.revise(details(1099, 4), record(), ACTOR, T1);
        listing.vetted(List.of(VettingFlag.PRICE_OUTLIER), T1); // flagged: waits for the console

        assertThat(listing.revise(
                        details("Renamed", AUTO_PARTS, List.of("img-1", "img-2"), 1099, 4), record(), ACTOR, T1))
                .isEmpty();
        assertThat(listing.getState().getVetting()).isEqualTo(Vetting.PENDING);
        assertThat(listing.getState().getFlags()).containsExactly(VettingFlag.PRICE_OUTLIER);

        var events = listing.revise(
                details("Renamed", TOOLS, List.of("img-1", "img-2"), 1099, 4), record(), ACTOR, T1.plusSeconds(5));
        assertThat(types(events)).containsExactly(ListingSubmitted.class); // already hidden
        assertThat(listing.getState().getRevetReasons()).containsExactly(MaterialField.PRICE, MaterialField.CATEGORY);
        assertThat(listing.getState().getFlags()).isEmpty();
    }

    @Test
    void draftsAndFirstSubmissionsAreUnaffected() {
        var draft = product(Vetting.DRAFT, ListingStatus.HIDDEN);
        assertThat(draft.revise(details(1099, 4), record(), ACTOR, T1)).isEmpty();
        assertThat(draft.getState().getVetting()).isEqualTo(Vetting.DRAFT);

        var pending = product(Vetting.PENDING, ListingStatus.HIDDEN);
        assertThat(pending.revise(details(1099, 4), record(), ACTOR, T1)).isEmpty();
        assertThat(pending.getState().getVetting()).isEqualTo(Vetting.DRAFT); // withdrawn, as before
    }
}
