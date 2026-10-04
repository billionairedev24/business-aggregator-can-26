package ca.northline.discovery.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyCollection;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import ca.northline.food.api.KitchenAvailability;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.merchants.api.PublicDirectory.PublicBusiness;
import ca.northline.support.MovableClock;
import ca.northline.trust.api.RatingQuery;
import java.time.Duration;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

/**
 * Engineering follow-ups (S-119 F5): the home page read every business of the city on each view. Within the cache's
 * ttl a market and language are read once; a visibility event drops the entry at once.
 */
class HomeCacheTest {

    final MovableClock clock = new MovableClock();
    final PublicDirectory directory = mock(PublicDirectory.class);
    final KitchenAvailability kitchens = mock(KitchenAvailability.class);
    final RatingQuery ratings = mock(RatingQuery.class);
    final CategorySource categories = mock(CategorySource.class);
    final HomeCache cache = new HomeCache(clock, Duration.ofSeconds(30));
    final HomeService home = new HomeService(directory, kitchens, ratings, categories, cache);

    static PublicBusiness provider(String id) {
        return new PublicBusiness(
                id,
                "Biz " + id,
                "provider",
                "trusted",
                "Townsville",
                "XX",
                id,
                null,
                List.of(),
                List.of(),
                List.of(),
                null,
                null,
                null,
                null);
    }

    @Test
    void aMarketIsReadOncePerTtlAndLanguage_andAgainAfterAVisibilityEvent() {
        when(directory.active(anyCollection(), any())).thenReturn(List.of(provider("p1")));
        when(kitchens.now(anyCollection())).thenReturn(Map.of());
        when(ratings.summaries(anyCollection())).thenAnswer(call -> {
            Collection<String> ids = call.getArgument(0);
            return ids.stream().collect(Collectors.toMap(id -> id, _ -> new RatingQuery.RatingSummary(4.5, 2)));
        });
        when(categories.byIds(anyCollection())).thenReturn(List.of());

        assertThat(home.of("Townsville", Locale.ENGLISH).providers()).isEqualTo(1);
        home.of(" townsville ", Locale.ENGLISH);
        home.of("Townsville", Locale.ENGLISH);
        verify(directory, times(1)).active(anyCollection(), eq("Townsville"));

        home.of("Townsville", Locale.CANADA_FRENCH); // another language: its own entry
        verify(directory, times(2)).active(anyCollection(), any());

        when(directory.active(anyCollection(), any())).thenReturn(List.of(provider("p1"), provider("p2")));
        cache.on(new MerchantApproved("e1", clock.instant(), "p2", "staff", "provider", "trusted")); // became visible
        assertThat(home.of("Townsville", Locale.ENGLISH).providers()).isEqualTo(2);

        clock.advance(Duration.ofSeconds(31));
        home.of("Townsville", Locale.ENGLISH);
        verify(directory, times(4)).active(anyCollection(), any());
    }
}
