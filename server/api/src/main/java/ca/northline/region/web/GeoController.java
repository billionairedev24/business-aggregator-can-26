package ca.northline.region.web;

import ca.northline.region.application.GeoUseCases.BrowseMarkets;
import ca.northline.region.application.GeoUseCases.ChooseAddress;
import ca.northline.region.application.GeoUseCases.JoinWaitlist;
import ca.northline.region.application.GeoUseCases.NamePlace;
import ca.northline.region.application.GeoUseCases.SuggestAddresses;
import ca.northline.region.application.GeoViews;
import ca.northline.region.application.GeoViews.Address;
import ca.northline.region.application.GeoViews.Place;
import ca.northline.region.application.GeoViews.Resolution;
import ca.northline.region.application.GeoViews.Suggestions;
import ca.northline.region.domain.GeoPoint;
import ca.northline.shared.NotFound;
import ca.northline.shared.WebhookRateLimiter;
import ca.northline.shared.security.MerchantAccess;
import jakarta.servlet.http.HttpServletRequest;
import java.time.Clock;
import java.time.Duration;
import java.util.Locale;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.AuthenticationCredentialsNotFoundException;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * The Location screen and the pill (S-47), public under {@code /api/v1/geo/**} (guests included; the consumer-bff
 * relays them). Address lookups cost money at Google, so each browsing session (the BFF's {@code X-Northline-Guest}),
 * else the caller's address, gets {@code northline.places.per-minute} of them per api instance → 429
 * {@code rate_limited}.
 *
 * <pre>
 * GET  /api/v1/geo/markets                         provinces with their markets and stages, the fallback market
 * GET  /api/v1/geo/autocomplete?q=&session=&near=  {items:[{placeId, main, secondary}], attribution}
 * GET  /api/v1/geo/places/{placeId}?session=       the address, its market, zone and waitlist
 * GET  /api/v1/geo/reverse?lat=&lng=               {label, city, province, market, zone} — 404 when nothing is there
 * GET  /api/v1/geo/resolve?lat=&lng=               {market, zone, waitlist}
 * POST /api/v1/geo/waitlist {regionId, email?}     201 joined · 200 already on it
 * </pre>
 */
@RestController
@RequestMapping("/api/v1/geo")
class GeoController {

    private final SuggestAddresses suggest;
    private final ChooseAddress choose;
    private final NamePlace name;
    private final BrowseMarkets markets;
    private final JoinWaitlist waitlist;
    private final MerchantAccess access;
    private final WebhookRateLimiter limiter;

    GeoController(
            SuggestAddresses suggest,
            ChooseAddress choose,
            NamePlace name,
            BrowseMarkets markets,
            JoinWaitlist waitlist,
            MerchantAccess access,
            Clock clock,
            @Value("${northline.places.per-minute:60}") int perMinute) {
        this.suggest = suggest;
        this.choose = choose;
        this.name = name;
        this.markets = markets;
        this.waitlist = waitlist;
        this.access = access;
        this.limiter = new WebhookRateLimiter(perMinute, clock);
    }

    @GetMapping("/markets")
    ResponseEntity<GeoViews.Markets> markets(Locale locale) {
        return ResponseEntity.ok()
                .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
                .body(markets.provinces(locale));
    }

    @GetMapping("/autocomplete")
    Suggestions autocomplete(
            @RequestParam(defaultValue = "") String q,
            @RequestParam(required = false) @Nullable String session,
            @RequestParam(required = false) @Nullable Double lat,
            @RequestParam(required = false) @Nullable Double lng,
            @RequestHeader(value = "X-Northline-Guest", required = false) @Nullable String guest,
            HttpServletRequest request,
            Locale locale) {
        var near = lat == null || lng == null ? null : new GeoPoint(lat, lng);
        if (q.strip().length() >= SuggestAddresses.MIN_INPUT) {
            limit(guest, request);
        }
        return suggest.suggest(q, session, locale, near);
    }

    @GetMapping("/places/{placeId}")
    Address place(
            @PathVariable String placeId,
            @RequestParam(required = false) @Nullable String session,
            @RequestHeader(value = "X-Northline-Guest", required = false) @Nullable String guest,
            HttpServletRequest request,
            Locale locale) {
        limit(guest, request);
        return choose.address(placeId, session, locale);
    }

    @GetMapping("/reverse")
    Place reverse(
            @RequestParam double lat,
            @RequestParam double lng,
            @RequestHeader(value = "X-Northline-Guest", required = false) @Nullable String guest,
            HttpServletRequest request,
            Locale locale) {
        var point = new GeoPoint(lat, lng);
        limit(guest, request);
        return name.name(point, locale).orElseThrow(() -> new NotFound("place", lat + "," + lng));
    }

    @GetMapping("/resolve")
    Resolution resolve(@RequestParam double lat, @RequestParam double lng) {
        return name.resolve(new GeoPoint(lat, lng));
    }

    /** @param email a guest's own address (required without a sign-in); ignored rows never reveal who is listed */
    record WaitlistRequest(
            @Nullable String regionId, @Nullable String email) {}

    @PostMapping("/waitlist")
    ResponseEntity<Map<String, Boolean>> join(@RequestBody WaitlistRequest body, Locale locale) {
        var added = waitlist.join(new JoinWaitlist.Command(
                body.regionId() == null ? "" : body.regionId(), signedIn(), body.email(), locale));
        return ResponseEntity.status(added ? HttpStatus.CREATED : HttpStatus.OK).body(Map.of("joined", true));
    }

    private @Nullable String signedIn() {
        try {
            return access.currentUser().userId();
        } catch (AuthenticationCredentialsNotFoundException _) {
            return null;
        }
    }

    private void limit(@Nullable String guest, HttpServletRequest request) {
        var key = guest != null && !guest.isBlank() ? "g:" + guest : "ip:" + request.getRemoteAddr();
        if (!limiter.allow(key)) {
            throw new TooManyLookups();
        }
    }

    static final class TooManyLookups extends RuntimeException {
        static final String MESSAGE = "Too many address lookups. Try again in a minute.";

        TooManyLookups() {
            super(MESSAGE, null, false, false);
        }
    }
}
