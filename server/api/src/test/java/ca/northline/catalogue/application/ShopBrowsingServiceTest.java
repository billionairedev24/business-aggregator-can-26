package ca.northline.catalogue.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.northline.catalogue.application.BrowseShop.Place;
import ca.northline.catalogue.application.ShopCatalogue.Category;
import ca.northline.catalogue.application.ShopCatalogue.ProductRow;
import ca.northline.catalogue.application.ShopViews.ProductCard;
import ca.northline.merchants.api.ShopDirectory;
import ca.northline.orders.api.DeliveryRuns;
import ca.northline.region.api.AgeRules;
import ca.northline.region.api.LanguageRules;
import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.MarketProfile;
import ca.northline.region.api.Regions;
import ca.northline.trust.api.RatingQuery;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S-190 (a location is never required): the Shop's markets and their order — every market without a place, nearest
 * first with a point, and with a market the other markets' products apart, nearest first, labelled not deliverable.
 * Three made-up markets: Northtown (centre 50.0, -100.0), Midtown (50.5, -100.0, 56 km away), Farville (no centre).
 */
class ShopBrowsingServiceTest {

    static final String DEPT = "shop.food.bakery";
    static final ZoneId ZONE = ZoneId.of("America/Regina");

    final ShopDirectory directory = mock(ShopDirectory.class);
    final ShopCatalogue catalogue = mock(ShopCatalogue.class);
    final CategoryCatalog categories = mock(CategoryCatalog.class);
    final MediaRepository media = mock(MediaRepository.class);
    final DeliveryRuns runs = mock(DeliveryRuns.class);
    final RatingQuery ratings = mock(RatingQuery.class);
    final Regions regions = mock(Regions.class);
    final AgeRules ageRules = mock(AgeRules.class);
    final ShopBrowsingService shop = new ShopBrowsingService(
            directory,
            catalogue,
            categories,
            media,
            runs,
            ratings,
            regions,
            ageRules,
            Clock.fixed(Instant.parse("2026-11-09T15:00:00Z"), ZoneOffset.UTC));

    static MarketProfile market(String city, @Nullable Double lat, @Nullable Double lng) {
        return new MarketProfile(
                "mkt-" + city.toLowerCase(Locale.ROOT),
                city,
                "XX",
                ZONE,
                lat,
                lng,
                LaunchStatus.LIVE,
                List.of(),
                LanguageRules.NONE);
    }

    static ProductRow row(String productId, String merchantId) {
        return new ProductRow(
                productId, "o-" + productId, merchantId, "Loaf " + productId, null, 500, null, 0, 1, null, DEPT);
    }

    @BeforeEach
    void markets() {
        when(runs.markets()).thenReturn(List.of("Farville", "Northtown", "Midtown"));
        when(runs.zone(anyString())).thenReturn(ZONE);
        for (var m : List.of("Farville", "Northtown", "Midtown")) {
            when(runs.market(m)).thenReturn(Optional.of(m));
            when(runs.upcoming(eq(m), any())).thenReturn(List.of());
        }
        when(runs.market("Nowhere")).thenReturn(Optional.empty());
        when(regions.market(eq("Northtown"), isNull())).thenReturn(Optional.of(market("Northtown", 50.0, -100.0)));
        when(regions.market(eq("Midtown"), isNull())).thenReturn(Optional.of(market("Midtown", 50.5, -100.0)));
        when(regions.market(eq("Farville"), isNull())).thenReturn(Optional.of(market("Farville", null, null)));
        when(regions.market(eq("Nowhere"), isNull())).thenReturn(Optional.empty());
        when(regions.platformZone()).thenReturn(ZONE);
        when(directory.shopsIn("Northtown"))
                .thenReturn(List.of(new ShopDirectory.Shop("n1", "North Bakery", "master", "Northtown")));
        when(directory.shopsIn("Midtown"))
                .thenReturn(List.of(new ShopDirectory.Shop("m1", "Mid Bakery", "trusted", "Midtown")));
        when(directory.shopsIn("Farville"))
                .thenReturn(List.of(new ShopDirectory.Shop("f1", "Far Bakery", "master", "Farville")));
        when(catalogue.categories(anyString()))
                .thenReturn(
                        List.of(new Category("shop.food", null, "Food"), new Category(DEPT, "shop.food", "Bakery")));
        when(categories.banned()).thenReturn(Set.of());
        when(catalogue.shops(anyCollection(), any(), anyCollection())).thenReturn(List.of());
        when(ratings.summaries(anyCollection())).thenReturn(Map.of());
        // each merchant sells one product; a call over several merchants answers theirs in this order
        when(catalogue.popular(anyCollection(), any(), anyCollection(), anyString(), anyInt()))
                .thenAnswer(call -> {
                    Collection<String> ids = call.getArgument(0);
                    return List.of(row("pf", "f1"), row("pn", "n1"), row("pm", "m1")).stream()
                            .filter(r -> ids.contains(r.merchantId()))
                            .toList();
                });
    }

    static List<String> ids(List<ProductCard> cards) {
        return cards.stream().map(ProductCard::productId).toList();
    }

    @Test
    void noPlace_everyMarketByPopularity_inOneRead_withNoDeliveryClaim() {
        var page = shop.department("bakery", Place.anywhere(), Locale.ENGLISH).orElseThrow();
        assertThat(page.market()).isNull();
        assertThat(page.served()).isTrue();
        assertThat(page.run()).isNull();
        assertThat(ids(page.products())).containsExactly("pf", "pn", "pm");
        assertThat(page.products()).allSatisfy(c -> assertThat(c.deliverable()).isNull());
        assertThat(page.products())
                .extracting(ProductCard::shipsFrom)
                .containsExactly("Farville", "Northtown", "Midtown");
        assertThat(page.nearby()).isEmpty();
        verify(runs, never()).upcoming(anyString(), any());
    }

    @Test
    void aPoint_nearestMarketFirst_aMarketWithoutACentreLast() {
        var page = shop.department("bakery", new Place(null, 50.45, -100.0), Locale.ENGLISH)
                .orElseThrow();
        assertThat(page.market()).isNull();
        assertThat(ids(page.products())).containsExactly("pm", "pn", "pf");
    }

    @Test
    void aMarket_itsOwnProductsDeliverable_theOthersApartNearestFirstAndLabelled() {
        var page = shop.department("bakery", Place.market("Northtown"), Locale.ENGLISH)
                .orElseThrow();
        assertThat(page.market()).isEqualTo("Northtown");
        assertThat(ids(page.products())).containsExactly("pn");
        assertThat(page.products().getFirst().deliverable()).isTrue();
        assertThat(ids(page.nearby())).containsExactly("pm", "pf");
        assertThat(page.nearby()).allSatisfy(c -> {
            assertThat(c.deliverable()).isFalse();
            assertThat(c.run()).isNull();
        });
        assertThat(page.nearby()).extracting(ProductCard::shipsFrom).containsExactly("Midtown", "Farville");
    }

    @Test
    void aCityWithoutDelivery_isNotServed_butStillListsWhatOtherMarketsSell() {
        var page = shop.landing(Place.market("Nowhere"), Locale.ENGLISH);
        assertThat(page.market()).isEqualTo("Nowhere");
        assertThat(page.served()).isFalse();
        assertThat(page.popular()).isEmpty();
        assertThat(page.nearby())
                .isNotEmpty()
                .allSatisfy(c -> assertThat(c.deliverable()).isFalse());
    }

    @Test
    void noMarketsAtAll_isNotServed_andEmpty() {
        when(runs.markets()).thenReturn(List.of());
        var page = shop.landing(Place.anywhere(), Locale.ENGLISH);
        assertThat(page.market()).isNull();
        assertThat(page.served()).isFalse();
        assertThat(page.popular()).isEmpty();
    }
}
