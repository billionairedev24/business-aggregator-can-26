package ca.northline.region.web;

import ca.northline.region.api.LaunchStatus;
import ca.northline.region.api.Markets;
import ca.northline.region.api.PrivacyLaw;
import ca.northline.region.api.Regions;
import java.time.Duration;
import java.time.ZoneId;
import java.util.List;
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
 * The region model for the web apps (S-134), public: {@code GET /api/v1/geo/regions?lang=} — every province with its
 * name in the language, launch status, time zones, privacy law and tax rate; every market with its city, province,
 * zone and status; the platform zone (dates that belong to no market) and the default province. The apps read place
 * names and zones from here instead of keeping a list of their own.
 */
@RestController
@RequestMapping("/api/v1/geo")
@RequiredArgsConstructor
class RegionController {

    private static final CacheControl CACHE =
            CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic();

    private final Regions regions;
    private final Markets markets;

    /** {@code nameIn} "in Alberta" / "au Québec", {@code nameOf} "Alberta" / "du Québec": for copy in the language. */
    record ProvinceResponse(
            String code,
            String name,
            String nameIn,
            String nameOf,
            LaunchStatus status,
            String timeZone,
            List<String> timeZones,
            PrivacyLaw privacyLaw,
            int taxBps) {}

    record MarketResponse(
            String id,
            String city,
            String province,
            String timeZone,
            LaunchStatus status,
            @Nullable Double lat,
            @Nullable Double lng) {}

    record RegionsResponse(
            String platformTimeZone,
            @Nullable String defaultProvince,
            List<ProvinceResponse> provinces,
            List<MarketResponse> markets) {}

    @GetMapping("/regions")
    ResponseEntity<RegionsResponse> regions(@RequestParam(required = false) @Nullable String lang, Locale locale) {
        var language = "fr".equals(lang) ? Locale.CANADA_FRENCH : "en".equals(lang) ? Locale.CANADA : locale;
        var provinces = regions.provinces().stream()
                .map(p -> new ProvinceResponse(
                        p.code(),
                        p.name(language),
                        p.nameIn(language),
                        p.nameOf(language),
                        p.status(),
                        p.zone().getId(),
                        p.timeZones().stream().map(ZoneId::getId).toList(),
                        p.privacyLaw(),
                        p.taxBps()))
                .toList();
        var cities = regions.markets().stream()
                .map(m -> new MarketResponse(
                        m.id(), m.city(), m.province(), m.zone().getId(), m.status(), m.lat(), m.lng()))
                .toList();
        return ResponseEntity.ok()
                .cacheControl(CACHE)
                .body(new RegionsResponse(
                        regions.platformZone().getId(), markets.defaultProvince(), provinces, cities));
    }
}
