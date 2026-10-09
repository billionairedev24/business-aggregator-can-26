package ca.northline.food.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.northline.food.api.KitchenAvailability;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.region.api.Markets;
import ca.northline.region.api.TaxRates;
import ca.northline.support.MovableClock;
import ca.northline.trust.api.RatingQuery;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * Engineering follow-ups (S-119 F5): the kitchens list's city-level reads (kitchens, open state, calendars, ratings)
 * happen once per market and ttl, whatever the customer's location; a kitchen pausing drops them at once.
 */
class KitchenListCacheTest {

    final MovableClock clock = new MovableClock();
    final PublicDirectory directory = mock(PublicDirectory.class);
    final KitchenAvailability availability = mock(KitchenAvailability.class);
    final RatingQuery ratings = mock(RatingQuery.class);
    final KitchenListCache cache = new KitchenListCache(clock, Duration.ofSeconds(30));
    final PublicKitchenService service = new PublicKitchenService(
            directory,
            availability,
            mock(KitchenCalendarStore.class),
            ratings,
            mock(KitchenMerchantFacts.class),
            mock(OrderableMenu.class),
            clock,
            mock(Markets.class),
            mock(TaxRates.class),
            cache);

    @Test
    void theCityIsReadOncePerTtl_forEveryLocation_andAgainWhenAKitchenPauses() {
        when(directory.active(anyCollection(), any())).thenReturn(List.of());
        when(availability.now(anyCollection())).thenReturn(Map.of());
        when(ratings.summaries(anyCollection())).thenReturn(Map.of());

        assertThat(service.kitchens("Townsville", null, null).items()).isEmpty();
        service.kitchens("Townsville", 51.0, -114.0);
        service.kitchens("TOWNSVILLE", 51.1, -114.1);
        verify(directory, times(1)).active(anyCollection(), any());
        verify(availability, times(1)).now(anyCollection());

        cache.onVisibilityChange(); // a kitchen paused
        service.kitchens("Townsville", null, null);
        verify(directory, times(2)).active(anyCollection(), any());
    }
}
