package ca.northline.discovery.application;

import ca.northline.food.api.KitchenAvailability;
import ca.northline.merchants.api.CategorySource;
import ca.northline.merchants.api.PublicDirectory;
import ca.northline.merchants.api.PublicDirectory.PublicBusiness;
import ca.northline.trust.api.RatingQuery;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * {@link ViewHome}: counts the city's active businesses (a {@code both} counts as a provider and a shop), the kitchens
 * open right now per cuisine, and picks three "Trusted near you" providers — best rated first, then most reviewed,
 * then the higher tier. Providers go to the customer, so "near" is the city; no distance is claimed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class HomeService implements ViewHome {

    static final int TRUSTED = 3;
    static final String MEAL_KITS = "meal_kits";
    private static final List<String> TIERS = List.of("master", "trusted", "registered");

    private final PublicDirectory directory;
    private final KitchenAvailability kitchens;
    private final RatingQuery ratings;
    private final CategorySource categories;

    @Override
    public HomeSummary of(String city, Locale locale) {
        var businesses = directory.active(Set.of("provider", "seller", "kitchen", "both"), city);
        var providers =
                only(businesses, b -> b.type().equals("provider") || b.type().equals("both"));
        var shops = only(businesses, b -> b.type().equals("seller") || b.type().equals("both"));
        var kitchenList = only(businesses, b -> b.type().equals("kitchen"));

        var perCategory = new HashMap<String, Integer>();
        businesses.forEach(b -> b.categoryIds().forEach(id -> perCategory.merge(id, 1, Integer::sum)));

        var status = kitchens.now(kitchenList.stream()
                .map(k -> new KitchenAvailability.Kitchen(k.merchantId(), k.province()))
                .toList());
        var perCuisine = new HashMap<String, Integer>();
        var open = 0;
        for (var k : kitchenList) {
            var s = status.get(k.merchantId());
            if (s == null || !s.open()) {
                continue;
            }
            open++;
            k.cuisines().forEach(c -> perCuisine.merge(c, 1, Integer::sum));
            if (s.fulfilment().contains(MEAL_KITS)) {
                perCuisine.merge(MEAL_KITS, 1, Integer::sum);
            }
        }
        return new HomeSummary(
                city, providers.size(), shops.size(), open, perCategory, perCuisine, trusted(providers, locale));
    }

    private List<HomeSummary.TrustedProvider> trusted(List<PublicBusiness> providers, Locale locale) {
        var rated = providers.stream()
                .map(p -> Map.entry(p, ratings.summary(p.merchantId())))
                .sorted(Comparator.<Map.Entry<PublicBusiness, RatingQuery.RatingSummary>>comparingDouble(
                                e -> -e.getValue().average())
                        .thenComparingInt(e -> -e.getValue().count())
                        .thenComparingInt(e -> tierRank(e.getKey().tier()))
                        .thenComparing(e -> e.getKey().displayName()))
                .limit(TRUSTED)
                .toList();
        var firstCategories = rated.stream()
                .flatMap(e -> e.getKey().categoryIds().stream().limit(1))
                .toList();
        var names = new HashMap<String, String>();
        categories.byIds(firstCategories).forEach(c -> names.put(c.id(), name(c.names(), locale)));
        return rated.stream()
                .map(e -> {
                    var p = e.getKey();
                    var first =
                            p.categoryIds().isEmpty() ? null : p.categoryIds().getFirst();
                    var label = first == null ? null : names.get(first);
                    var category = first == null || label == null ? null : new HomeSummary.Category(first, label);
                    return new HomeSummary.TrustedProvider(
                            p.merchantId(),
                            p.displayName(),
                            p.slug(),
                            p.tier(),
                            p.brandColor(),
                            category,
                            e.getValue().average(),
                            e.getValue().count());
                })
                .toList();
    }

    private static List<PublicBusiness> only(List<PublicBusiness> all, Predicate<PublicBusiness> test) {
        return all.stream().filter(test).toList();
    }

    private static int tierRank(String tier) {
        var i = TIERS.indexOf(tier);
        return i < 0 ? TIERS.size() : i;
    }

    private static String name(Map<String, String> names, Locale locale) {
        var fr = names.get("fr");
        return locale.getLanguage().equals("fr") && fr != null ? fr : names.getOrDefault("en", "");
    }
}
