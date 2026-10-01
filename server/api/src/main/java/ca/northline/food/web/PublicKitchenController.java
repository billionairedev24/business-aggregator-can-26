package ca.northline.food.web;

import ca.northline.food.application.PublicKitchenUseCases;
import ca.northline.food.application.PublicKitchenViews.Kitchens;
import ca.northline.food.application.PublicKitchenViews.Restaurant;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public food reads of the consumer site (S-57; guests included):
 *
 * <pre>
 * GET /api/v1/public/kitchens?city=&amp;lat=&amp;lng=   the city's kitchens: open now / opens at, times, distance, fees
 * GET /api/v1/public/kitchens/{slug}?lat=&amp;lng=     one kitchen and its orderable menu, combos and scheduled windows
 * </pre>
 *
 * Open states change by the minute, so answers are cacheable for 30 s only.
 */
@RestController
@RequestMapping("/api/v1/public/kitchens")
@RequiredArgsConstructor
class PublicKitchenController {

    private final PublicKitchenUseCases kitchens;

    @GetMapping
    ResponseEntity<Kitchens> kitchens(
            @RequestParam(required = false) @Nullable String city,
            @RequestParam(required = false) @Nullable Double lat,
            @RequestParam(required = false) @Nullable Double lng) {
        var name = city == null ? "" : city.strip();
        if (name.isEmpty()) {
            throw RuleViolation.of("city", "required", "Choose a city.");
        }
        if (name.length() > 60) {
            throw RuleViolation.of("city", "length", "At most 60 characters.");
        }
        checkPoint(lat, lng);
        return cached(kitchens.kitchens(name, lat, lng));
    }

    @GetMapping("/{slug}")
    ResponseEntity<Restaurant> restaurant(
            @PathVariable String slug,
            @RequestParam(required = false) @Nullable Double lat,
            @RequestParam(required = false) @Nullable Double lng) {
        checkPoint(lat, lng);
        return cached(kitchens.restaurant(slug, lat, lng));
    }

    private static <T> ResponseEntity<T> cached(T body) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofSeconds(30)).cachePublic())
                .body(body);
    }

    private static void checkPoint(@Nullable Double lat, @Nullable Double lng) {
        if (lat != null && (lat < -90 || lat > 90)) {
            throw RuleViolation.of("lat", "range", "Latitude must be between -90 and 90.");
        }
        if (lng != null && (lng < -180 || lng > 180)) {
            throw RuleViolation.of("lng", "range", "Longitude must be between -180 and 180.");
        }
    }
}
