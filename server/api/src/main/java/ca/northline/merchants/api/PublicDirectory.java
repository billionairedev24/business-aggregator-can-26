package ca.northline.merchants.api;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

/**
 * What the consumer site may show about businesses (S-46): approved ({@code active}) businesses only, never the legal
 * name, contact or documents. Read by the consumer landing pages (home, food) through their own modules.
 */
public interface PublicDirectory {

    /**
     * Active businesses of these types ({@code provider|seller|kitchen|both}) in {@code city} (case-insensitive; the
     * city column set at onboarding), in no particular order.
     */
    List<PublicBusiness> active(Collection<String> types, String city);

    /** An active business by its storefront slug. */
    Optional<PublicBusiness> bySlug(String slug);

    /**
     * @param slug the storefront's slug ({@code /providers/<slug>}, {@code /food/<slug>}), or null without a storefront
     * @param categoryIds approved categories, the one chosen first leading
     * @param cuisines a kitchen's cuisine codes ({@code vietnamese}, {@code pizza} … as onboarding offers them)
     * @param dietary a kitchen's dietary guarantees from onboarding ({@code halal}, {@code vegan} …)
     * @param address the kitchen's / pickup address the business gave at onboarding, or null
     * @param lat where the business is ({@code merchants.locations}, S-43), or null when not located yet
     * @param serviceRadiusKm how far it travels or delivers, or null (a kitchen then uses its own delivery radius)
     */
    record PublicBusiness(
            String merchantId,
            String displayName,
            String type,
            String tier,
            @Nullable String city,
            @Nullable String province,
            @Nullable String slug,
            @Nullable String brandColor,
            List<String> categoryIds,
            List<String> cuisines,
            List<String> dietary,
            @Nullable String address,
            @Nullable Double lat,
            @Nullable Double lng,
            @Nullable Double serviceRadiusKm) {

        public PublicBusiness {
            categoryIds = List.copyOf(categoryIds);
            cuisines = List.copyOf(cuisines);
            dietary = List.copyOf(dietary);
        }
    }
}
