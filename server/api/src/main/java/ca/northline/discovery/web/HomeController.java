package ca.northline.discovery.web;

import ca.northline.discovery.application.HomeSummary;
import ca.northline.discovery.application.ViewHome;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.util.Locale;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * {@code GET /api/v1/public/home?city={city}} — public (guests included), the same for everyone in a city, so it may
 * be cached for a minute. Unknown cities answer zeros, not 404: the page then offers to change the location.
 */
@RestController
@RequestMapping("/api/v1/public/home")
@RequiredArgsConstructor
class HomeController {

    static final String CITY_REQUIRED = "Choose a city.";
    static final String CITY_TOO_LONG = "At most 60 characters.";

    private final ViewHome viewHome;

    @GetMapping
    ResponseEntity<HomeSummary> home(@RequestParam(required = false) @Nullable String city, Locale locale) {
        var name = city == null ? "" : city.strip();
        if (name.isEmpty()) {
            throw RuleViolation.of("city", "required", CITY_REQUIRED);
        }
        if (name.length() > ViewHome.CITY_MAX) {
            throw RuleViolation.of("city", "length", CITY_TOO_LONG);
        }
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(1)).cachePublic())
                .body(viewHome.of(name, locale));
    }
}
