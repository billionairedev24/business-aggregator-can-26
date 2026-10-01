package ca.northline.catalogue.web;

import ca.northline.catalogue.application.BrowseShop;
import ca.northline.catalogue.application.ShopViews;
import ca.northline.region.api.FallbackMarket;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.time.Duration;
import java.util.Locale;
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
 * The consumer Shop's public reads (S-49; {@code /api/v1/public/**} is open to guests in SecurityConfig). The pages
 * are server-rendered and the same for everyone, so the market and language are query parameters (cacheable), not the
 * visitor's session. The read models are purpose-built for these pages and serialized as they are.
 *
 * <pre>
 * GET /api/v1/public/shop?market=&amp;lang=fr                         the landing page (no market = the fallback market)
 * GET /api/v1/public/shop/departments/{slug}?market=&amp;lang=         a department (404 for an unknown slug)
 * GET /api/v1/public/shop/products/{productId}?market=&amp;lang=      a product and the market's offers (S-50)
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/public/shop")
@RequiredArgsConstructor
class PublicShopController {

    static final int MAX_MARKET = 60;
    static final String MARKET_MESSAGE = "Choose a city.";
    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofSeconds(60)).cachePublic();

    private final BrowseShop shop;
    private final FallbackMarket fallback;

    @GetMapping
    ResponseEntity<ShopViews.Landing> landing(
            @RequestParam(required = false) @Nullable String market,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        return ResponseEntity.ok().cacheControl(CACHE).body(shop.landing(market(market), locale(lang, locale)));
    }

    @GetMapping("/departments/{slug}")
    ResponseEntity<ShopViews.Department> department(
            @PathVariable String slug,
            @RequestParam(required = false) @Nullable String market,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        var department = shop.department(slug, market(market), locale(lang, locale))
                .orElseThrow(() -> new NotFound("department", slug));
        return ResponseEntity.ok().cacheControl(CACHE).body(department);
    }

    @GetMapping("/products/{productId}")
    ResponseEntity<ShopViews.ProductPage> product(
            @PathVariable String productId,
            @RequestParam(required = false) @Nullable String market,
            @RequestParam(required = false) @Nullable String lang,
            Locale locale) {
        var product = shop.product(productId, market(market), locale(lang, locale))
                .orElseThrow(() -> new NotFound("product", productId));
        return ResponseEntity.ok().cacheControl(CACHE).body(product);
    }

    /** The URL's market, else the region's fallback market (S-47: the default province's first live market). */
    String market(@Nullable String market) {
        var value = market == null
                ? fallback.fallback().map(FallbackMarket.City::city).orElse("")
                : market.strip();
        if (value.isEmpty() || value.length() > MAX_MARKET) {
            throw RuleViolation.of("market", "length", MARKET_MESSAGE);
        }
        return value;
    }

    /** {@code lang=fr|en} wins over Accept-Language: the server-rendered page asks in the page's language. */
    static Locale locale(@Nullable String lang, Locale fallback) {
        if ("fr".equals(lang)) {
            return Locale.CANADA_FRENCH;
        }
        return "en".equals(lang) ? Locale.CANADA : fallback;
    }
}
