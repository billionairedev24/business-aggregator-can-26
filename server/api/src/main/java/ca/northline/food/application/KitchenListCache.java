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
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

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

    /**
     * A business's visibility or what the page shows changed: dropped once the change has committed (a read in between
     * would keep the old rows otherwise). A plain listener, not an {@code @ApplicationModuleListener}: a cache drop
     * needs no outbox row per event, and a lost one only means the entry lives out its ttl.
     */
    @EventListener({
        MerchantApproved.class,
        MerchantSuspended.class,
        MerchantReinstated.class,
        MerchantSearchVisibilityChanged.class,
        MerchantRenamed.class,
        MerchantCategoriesChanged.class,
        MerchantTierChanged.class,
        StorefrontPublished.class,
        KitchenPaused.class,
        KitchenResumed.class,
        KitchenAutoPaused.class,
        KitchenAutoResumed.class,
        MenuPublished.class
    })
    void onVisibilityChange() {
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    invalidate();
                }
            });
        } else {
            invalidate();
        }
    }
}
