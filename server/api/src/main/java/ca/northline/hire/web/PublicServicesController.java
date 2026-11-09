package ca.northline.hire.web;

import ca.northline.availability.api.ServiceAreas.Place;
import ca.northline.hire.application.BrowseServices.Category;
import ca.northline.hire.application.BrowseServices.Landing;
import ca.northline.hire.application.BrowseServices.ListCategories;
import ca.northline.hire.application.BrowseServices.ListProviders;
import ca.northline.hire.application.BrowseServices.Providers;
import ca.northline.hire.application.BrowseServices.ViewCategory;
import ca.northline.shared.PlaceParams;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public reads of the Services journey (S-53; {@code GET /api/v1/public/**} is open in SecurityConfig). The consumer
 * app renders the landing and category pages on the server from these; the provider list depends on the customer's
 * location and is fetched in the browser. The read models are serialized as they are (like the Studio dashboard): they
 * exist only for these screens.
 */
@RestController
@RequiredArgsConstructor
class PublicServicesController {

    static final String POINT_INCOMPLETE = "Send both lat and lng, or neither.";
    static final String POINT_RANGE = "That location is outside the map.";

    private final ListCategories landing;
    private final ViewCategory categories;
    private final ListProviders providers;

    @GetMapping("/api/v1/public/services")
    ResponseEntity<Landing> landing(@RequestParam(defaultValue = "en") String lang) {
        return cached(landing.landing(Languages.of(lang)), Duration.ofMinutes(1));
    }

    @GetMapping("/api/v1/public/services/{slug}")
    ResponseEntity<Category> category(@PathVariable String slug, @RequestParam(defaultValue = "en") String lang) {
        return cached(categories.category(slug, Languages.of(lang)), Duration.ofMinutes(1));
    }

    /** The category's providers covering the customer: {@code lat}/{@code lng} from the device, else {@code city}. */
    @GetMapping("/api/v1/public/services/{slug}/providers")
    ResponseEntity<Providers> providers(
            @PathVariable String slug,
            @RequestParam(required = false) @Nullable Double lat,
            @RequestParam(required = false) @Nullable Double lng,
            @RequestParam(required = false) @Nullable String city,
            @RequestParam(defaultValue = "en") String lang) {
        return cached(providers.providers(slug, place(lat, lng, city), Languages.of(lang)), Duration.ofSeconds(30));
    }

    /**
     * The customer's place, each part optional (S-190: none = every provider, by trust; the region's fallback market is
     * no longer assumed). A malformed city is refused ({@link PlaceParams}).
     */
    static Place place(@Nullable Double lat, @Nullable Double lng, @Nullable String city) {
        if ((lat == null) != (lng == null)) {
            throw RuleViolation.of(lat == null ? "lat" : "lng", "required", POINT_INCOMPLETE);
        }
        if (lat != null && (lat < -90 || lat > 90)) {
            throw RuleViolation.of("lat", "range", POINT_RANGE);
        }
        if (lng != null && (lng < -180 || lng > 180)) {
            throw RuleViolation.of("lng", "range", POINT_RANGE);
        }
        return new Place(lat, lng, PlaceParams.city("city", city));
    }

    private static <T> ResponseEntity<T> cached(T body, Duration maxAge) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(maxAge).cachePublic())
                .body(body);
    }
}
