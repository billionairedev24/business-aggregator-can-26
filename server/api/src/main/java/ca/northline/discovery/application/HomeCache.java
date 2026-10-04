package ca.northline.discovery.application;

import ca.northline.food.api.KitchenAutoPaused;
import ca.northline.food.api.KitchenAutoResumed;
import ca.northline.food.api.KitchenPaused;
import ca.northline.food.api.KitchenResumed;
import ca.northline.food.api.MenuPublished;
import ca.northline.merchants.api.MerchantApproved;
import ca.northline.merchants.api.MerchantCategoriesChanged;
import ca.northline.merchants.api.MerchantReinstated;
import ca.northline.merchants.api.MerchantRenamed;
import ca.northline.merchants.api.MerchantSearchVisibilityChanged;
import ca.northline.merchants.api.MerchantSuspended;
import ca.northline.merchants.api.MerchantTierChanged;
import ca.northline.merchants.api.StorefrontPublished;
import ca.northline.shared.ShortCache;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.modulith.events.ApplicationModuleListener;
import org.springframework.stereotype.Component;

/**
 * Engineering follow-ups (S-119 F5): the home page read every business of the city and every kitchen's calendar on
 * each view (≈ 60 % of the database's time in the load tests). Its summary is the same for everyone in a market and
 * language, so it is kept {@code northline.public-pages.cache-ttl} (default 30 s) per instance, keyed by market and
 * language, and dropped when a business becomes visible or hidden, changes what the page shows (name, categories,
 * tier, storefront) or a kitchen opens, pauses or publishes a menu. Other replicas see such a change when their entry
 * expires.
 */
@Component
class HomeCache {

    static final int MAX_ENTRIES = 500;

    private record Key(String market, String language) {}

    private final ShortCache<Key, HomeSummary> cache;

    HomeCache(Clock clock, @Value("${northline.public-pages.cache-ttl:30s}") Duration ttl) {
        cache = new ShortCache<>(clock, ttl, MAX_ENTRIES);
    }

    HomeSummary get(String city, Locale locale, Supplier<HomeSummary> load) {
        return cache.get(new Key(city.strip().toLowerCase(Locale.ROOT), locale.getLanguage()), load);
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
