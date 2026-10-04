package ca.northline.food.application;

import ca.northline.food.api.KitchenAutoPaused;
import ca.northline.food.api.KitchenAutoResumed;
import ca.northline.food.api.KitchenAvailability.KitchenStatus;
import ca.northline.food.api.KitchenPaused;
import ca.northline.food.api.KitchenResumed;
import ca.northline.food.api.MenuPublished;
import ca.northline.food.application.KitchenCalendarStore.CalendarRow;
import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.MerchantCategoriesChanged;
import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.merchants.api.MerchantSearchVisibilityChanged;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.MerchantTierChanged;
import ca.northline.merchants.api.PublicDirectory.PublicBusiness;
import ca.northline.merchants.api.StorefrontPublished;
import ca.northline.shared.ShortCache;
import ca.northline.trust.api.RatingQuery;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Engineering follow-ups (S-119 F5): the kitchens list read every kitchen of the city, their calendars (twice: open
 * state and the card's facts) and ratings on each view (with home, ≈ 60 % of the database's time in the load tests).
 * That city-level part is the same for everyone, so it is kept {@code northline.public-pages.cache-ttl} (default 30 s)
 * per instance, keyed by market; the customer's distance, fees and the order of the cards are worked out per request.
 * It is dropped when a business becomes visible or hidden or changes what a card shows, or a kitchen opens, pauses or
 * publishes a menu. Other replicas see such a change when their entry expires.
 */
@Component
class KitchenListCache {

    static final int MAX_ENTRIES = 500;

    /** What the list shows for a market, before the customer's location. */
    record CityKitchens(
            List<PublicBusiness> kitchens,
            Map<String, KitchenStatus> status,
            Map<String, CalendarRow> rows,
            Map<String, RatingQuery.RatingSummary> ratings) {

        CityKitchens {
            kitchens = List.copyOf(kitchens);
            status = Map.copyOf(status);
            rows = Map.copyOf(rows);
            ratings = Map.copyOf(ratings);
        }
    }

    private final ShortCache<String, CityKitchens> cache;

    KitchenListCache(Clock clock, @Value("${northline.public-pages.cache-ttl:30s}") Duration ttl) {
        cache = new ShortCache<>(clock, ttl, MAX_ENTRIES);
    }

    CityKitchens get(String city, Supplier<CityKitchens> load) {
        return cache.get(city.strip().toLowerCase(Locale.ROOT), load);
    }

    void invalidate() {
        cache.invalidateAll();
    }

    @ApplicationModuleListener
    void on(MerchantApproved event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantSuspended event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantReinstated event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantSearchVisibilityChanged event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantRenamed event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantCategoriesChanged event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MerchantTierChanged event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(StorefrontPublished event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(KitchenPaused event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(KitchenResumed event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(KitchenAutoPaused event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(KitchenAutoResumed event) {
        invalidate();
    }

    @ApplicationModuleListener
    void on(MenuPublished event) {
        invalidate();
    }
}
