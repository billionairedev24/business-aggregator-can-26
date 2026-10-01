package ca.northline.region.application;

import ca.northline.region.api.FallbackMarket;
import ca.northline.region.api.Markets;
import ca.northline.region.api.TaxRates;
import ca.northline.region.application.GeoUseCases.BrowseMarkets;
import ca.northline.region.application.GeoUseCases.ChooseAddress;
import ca.northline.region.application.GeoUseCases.JoinWaitlist;
import ca.northline.region.application.GeoUseCases.NamePlace;
import ca.northline.region.application.GeoUseCases.SuggestAddresses;
import ca.northline.region.application.GeoViews.Address;
import ca.northline.region.application.GeoViews.Market;
import ca.northline.region.application.GeoViews.Place;
import ca.northline.region.application.GeoViews.Province;
import ca.northline.region.application.GeoViews.Resolution;
import ca.northline.region.application.GeoViews.Suggestion;
import ca.northline.region.application.GeoViews.Suggestions;
import ca.northline.region.application.GeoViews.Waitlist;
import ca.northline.region.application.GeoViews.Zone;
import ca.northline.region.application.MarketStore.RegionRow;
import ca.northline.region.domain.GeoMessages;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.PlaceParts;
import ca.northline.region.domain.Stage;
import ca.northline.shared.Conflict;
import ca.northline.shared.Ids;
import ca.northline.shared.NotFound;
import ca.northline.shared.RuleViolation;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * The Location screen and the pill (S-47): suggestions and details from the {@link PlacesAutocomplete} port, resolved
 * to a market and zone by PostGIS; provinces and markets; the waitlist.
 */
@Service
@RequiredArgsConstructor
class GeoService implements SuggestAddresses, ChooseAddress, NamePlace, BrowseMarkets, JoinWaitlist, FallbackMarket {

    private final PlacesAutocomplete places;
    private final MarketStore markets;
    private final Markets served;
    private final TaxRates taxes;

    @Override
    public Suggestions suggest(String input, @Nullable String sessionToken, Locale locale, @Nullable GeoPoint near) {
        var q = input.strip();
        if (q.length() > MAX_INPUT) {
            throw RuleViolation.of("q", "length", GeoMessages.TOO_LONG);
        }
        checkSession(sessionToken);
        if (q.length() < MIN_INPUT) {
            return new Suggestions(List.of(), places.attribution());
        }
        return new Suggestions(
                places.autocomplete(q, sessionToken, locale, near).stream()
                        .map(p -> new Suggestion(p.placeId(), p.main(), p.secondary()))
                        .toList(),
                places.attribution());
    }

    @Override
    @Transactional(readOnly = true)
    public Address address(String placeId, @Nullable String sessionToken, Locale locale) {
        checkSession(sessionToken);
        var place = places.details(placeId, sessionToken, locale).orElseThrow(() -> new NotFound("place", placeId));
        if (!place.inCanada()) {
            throw RuleViolation.of("placeId", "canada", GeoMessages.NOT_IN_CANADA);
        }
        var resolution = resolve(place.point(), place.province());
        var city = resolution.market() != null && resolution.live()
                ? resolution.market().city()
                : place.city();
        var street = place.street();
        return new Address(
                place.placeId(),
                city == null ? street : street + ", " + city,
                street,
                place.city(),
                place.province(),
                place.postalCode(),
                place.neighbourhood(),
                place.point().lat(),
                place.point().lng(),
                resolution);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<Place> name(GeoPoint point, Locale locale) {
        var found = places.reverse(point, locale);
        var resolution = resolve(point, found.map(PlaceParts::province).orElse(null));
        var market = resolution.market();
        var city = market != null ? market.city() : found.map(PlaceParts::city).orElse(null);
        if (city == null) {
            return Optional.empty();
        }
        var area = found.map(PlaceParts::neighbourhood)
                .or(() -> Optional.ofNullable(resolution.zone()).map(Zone::name))
                .filter(n -> !n.equalsIgnoreCase(city))
                .orElse(null);
        return Optional.of(new Place(
                area == null ? city : area + ", " + city,
                city,
                found.map(PlaceParts::province).orElse(market == null ? null : market.province()),
                market,
                resolution.zone()));
    }

    @Override
    @Transactional(readOnly = true)
    public Resolution resolve(GeoPoint point) {
        return resolve(point, null);
    }

    private Resolution resolve(GeoPoint point, @Nullable String province) {
        var market = markets.marketAt(point);
        var zone = market.filter(m -> m.stage().live() || m.stage() == Stage.PILOT)
                .flatMap(m -> markets.zoneAt(m.id(), point))
                .map(z -> new Zone(
                        z.id(), z.name(), z.runsPerDay(), z.feeStdCents(), z.feePlusCents(), z.minBasketCents()))
                .orElse(null);
        Waitlist waitlist = null;
        if (market.isEmpty() || !market.get().stage().live()) {
            waitlist = market.or(() -> province == null ? Optional.empty() : markets.nearestMarket(province, point))
                    .or(() -> province == null ? Optional.empty() : markets.province(province))
                    .map(r -> new Waitlist(r.id(), r.market() ? cityOf(r) : r.nameEn(), stage(r)))
                    .orElse(null);
        }
        return new Resolution(market.map(GeoService::market).orElse(null), zone, waitlist);
    }

    @Override
    @Transactional(readOnly = true)
    public GeoViews.Markets provinces(Locale locale) {
        var all = markets.regions();
        var fr = locale.getLanguage().equals("fr");
        var order = List.copyOf(served.served());
        var provinces = all.stream()
                .filter(r -> !r.market())
                .filter(p -> served.serves(p.province())
                        || p.stage() != Stage.OFF
                        || all.stream().anyMatch(m -> m.market() && p.id().equals(m.parentId())))
                // served provinces first, in configuration order, then the others by sort
                .sorted(java.util.Comparator.comparingInt((RegionRow p) -> {
                    var i = order.indexOf(p.province());
                    return i < 0 ? Integer.MAX_VALUE : i;
                }))
                .map(p -> new Province(
                        p.province(),
                        fr && p.nameFr() != null ? p.nameFr() : p.nameEn(),
                        stage(p),
                        taxes.bpsFor(p.province()),
                        all.stream()
                                .filter(m -> m.market() && p.id().equals(m.parentId()))
                                .map(GeoService::market)
                                .toList()))
                .toList();
        var firstLive = provinces.stream()
                .filter(p -> p.stage().live())
                .sorted(java.util.Comparator.comparing((Province p) -> !p.code().equals(served.defaultProvince())))
                .flatMap(p -> p.markets().stream().filter(m -> m.stage().live()))
                .findFirst()
                .orElse(null);
        return new GeoViews.Markets(provinces, firstLive);
    }

    @Override
    public java.util.Optional<FallbackMarket.City> fallback() {
        var m = provinces(Locale.ENGLISH).fallback();
        return m == null
                ? java.util.Optional.empty()
                : java.util.Optional.of(new FallbackMarket.City(m.city(), m.province()));
    }

    /** A province listed in SEARCH_MARKETS is live whatever its row says (one list of served markets). */
    private Stage stage(RegionRow r) {
        return !r.market() && served.serves(r.province()) ? Stage.LIVE : r.stage();
    }

    @Override
    @Transactional
    public boolean join(Command command) {
        var region = markets.region(command.regionId())
                .orElseThrow(() -> RuleViolation.of("regionId", "required", GeoMessages.REGION_REQUIRED));
        var email = command.email() == null || command.email().isBlank()
                ? null
                : command.email().strip();
        if (command.userId() == null && email == null) {
            throw RuleViolation.of("email", "required", GeoMessages.EMAIL_REQUIRED);
        }
        if (email != null
                && (email.length() > 254 || !GeoMessages.EMAIL.matcher(email).matches())) {
            throw RuleViolation.of("email", "format", GeoMessages.EMAIL_FORMAT);
        }
        // a served province still has addresses outside its markets: only a live market refuses
        if (region.market() && region.stage().live()) {
            throw new Conflict("region_live", "Northline is already live here.");
        }
        return markets.joinWaitlist(
                Ids.next(),
                region.id(),
                command.userId(),
                email,
                command.locale().getLanguage().equals("fr") ? "fr" : "en");
    }

    private static Market market(RegionRow r) {
        return new Market(r.id(), cityOf(r), r.province(), r.stage(), r.lat(), r.lng());
    }

    private static String cityOf(RegionRow r) {
        return r.city() == null ? r.nameEn() : r.city();
    }

    private static void checkSession(@Nullable String token) {
        if (token != null && !GeoMessages.SESSION.matcher(token).matches()) {
            throw RuleViolation.of("session", "format", GeoMessages.SESSION_FORMAT);
        }
    }
}
