package ca.northline.discovery.application;

import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * The home page's numbers for one city (design 06 {@code home}): the Services / Shop / Food chips ("412 pros",
 * "58 shops", "42 open"), businesses per approved category (departments and service categories), open kitchens per
 * cuisine (plus {@code meal_kits}), and "Trusted near you". Serialized as is ({@code GET /api/v1/public/home}).
 *
 * @param categories active businesses per category id ({@code shop.food-and-grocery.bakery} → 7)
 * @param cuisines kitchens open now per cuisine code ({@code vietnamese} → 4; {@code meal_kits} = kitchens offering
 *     meal kits on the pooled run)
 */
public record HomeSummary(
        String city,
        int providers,
        int shops,
        int kitchensOpen,
        Map<String, Integer> categories,
        Map<String, Integer> cuisines,
        List<TrustedProvider> trusted) {

    public HomeSummary {
        categories = Map.copyOf(categories);
        cuisines = Map.copyOf(cuisines);
        trusted = List.copyOf(trusted);
    }

    /**
     * @param slug the public page ({@code /providers/<slug>}), or null before the business published one
     * @param category the business's first approved category, named in the request's language
     * @param rating star average (one decimal), 0 without reviews
     */
    public record TrustedProvider(
            String merchantId,
            String name,
            @Nullable String slug,
            String tier,
            @Nullable String brandColor,
            @Nullable Category category,
            double rating,
            int reviews) {}

    public record Category(String id, String name) {}
}
