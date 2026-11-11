package ca.northline.region.adapters;

import ca.northline.region.application.PlacesAutocomplete;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.PlaceParts;
import java.text.Normalizer;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code PLACES_PROVIDER=local}: a fixed list of Canadian addresses read from {@code places-fixtures/addresses.json}
 * (design 06's "1204 17 …" suggestions, one per dev-seed market and a few outside them) so every branch of the Location
 * screen can be tried without a Google key. Matching: every word typed
 * must start a word of the address. Reverse: the nearest fixture within 3 km.
 */
class FakePlaces implements PlacesAutocomplete {

    private static final double REVERSE_KM = 3;

    private static PlaceParts place(
            String id,
            String number,
            String route,
            String neighbourhood,
            String city,
            String province,
            String postal,
            double lat,
            double lng) {
        var formatted = "%s %s, %s, %s %s, Canada".formatted(number, route, city, province, postal);
        return new PlaceParts(
                "ChIJfake" + id,
                formatted,
                number,
                route,
                neighbourhood,
                city,
                province,
                postal,
                "CA",
                new GeoPoint(lat, lng));
    }

    /** The fixture addresses ({@code places-fixtures/addresses.json}): local runs and tests only. */
    static final List<PlaceParts> FIXTURES = load();

    private static List<PlaceParts> load() {
        try (var in = new ClassPathResource("places-fixtures/addresses.json").getInputStream()) {
            var out = new java.util.ArrayList<PlaceParts>();
            for (var a : JsonMapper.builder().build().readTree(in).path("addresses")) {
                out.add(place(
                        a.path("id").asString(),
                        a.path("number").asString(),
                        a.path("route").asString(),
                        a.path("neighbourhood").asString(),
                        a.path("city").asString(),
                        a.path("province").asString(),
                        a.path("postalCode").asString(),
                        a.path("lat").asDouble(),
                        a.path("lng").asDouble()));
            }
            return List.copyOf(out);
        } catch (java.io.IOException e) {
            throw new IllegalStateException("places-fixtures/addresses.json is missing", e);
        }
    }

    @Override
    public String attribution() {
        return "Northline test addresses";
    }

    @Override
    public List<Prediction> autocomplete(
            String input, @Nullable String sessionToken, Locale locale, @Nullable GeoPoint near) {
        var words = fold(input).split("[^a-z0-9]+");
        return FIXTURES.stream()
                .filter(p -> matches(p, words))
                .sorted(Comparator.comparingDouble(p -> near == null ? 0 : near.kmTo(p.point())))
                .limit(5)
                .map(p -> new Prediction(p.placeId(), p.street(), secondary(p)))
                .toList();
    }

    @Override
    public Optional<PlaceParts> details(String placeId, @Nullable String sessionToken, Locale locale) {
        return FIXTURES.stream().filter(p -> p.placeId().equals(placeId)).findFirst();
    }

    @Override
    public Optional<PlaceParts> reverse(GeoPoint point, Locale locale) {
        return FIXTURES.stream()
                .filter(p -> p.point().kmTo(point) <= REVERSE_KM)
                .min(Comparator.comparingDouble(p -> p.point().kmTo(point)));
    }

    /**
     * S-192: a few postal codes outside Canada, so the location dialog's country-agnostic "Apply" can be tried locally
     * (fixtures, not configuration).
     */
    static final List<PlaceParts> POSTAL_ABROAD = List.of(
            new PlaceParts(
                    "postal:US-90210",
                    "Beverly Hills, CA 90210, USA",
                    null,
                    null,
                    null,
                    "Beverly Hills",
                    "CA",
                    "90210",
                    "US",
                    new GeoPoint(34.0901, -118.4065)),
            new PlaceParts(
                    "postal:GB-SW1A1AA",
                    "London SW1A 1AA, UK",
                    null,
                    null,
                    null,
                    "London",
                    "ENG",
                    "SW1A 1AA",
                    "GB",
                    new GeoPoint(51.501, -0.1416)),
            new PlaceParts(
                    "postal:FR-75008",
                    "75008 Paris, France",
                    null,
                    null,
                    null,
                    "Paris",
                    "IDF",
                    "75008",
                    "FR",
                    new GeoPoint(48.8763, 2.3183)));

    /**
     * The fixture whose postal code is the one typed (spaces ignored) or, for three or more characters, starts with it
     * (a Canadian FSA, "T2T"): the area of that fixture, without its street.
     */
    @Override
    public Optional<PlaceParts> postalCode(String code, @Nullable String country, Locale locale) {
        return exactOrPrefix(code, country).or(() -> sameCanadianArea(code, country));
    }

    private static final java.util.regex.Pattern CANADIAN_POSTAL =
            java.util.regex.Pattern.compile("[ABCEGHJ-NPRSTVXY]\\d[A-Z](\\d[A-Z]\\d)?");

    /**
     * A Canadian code that isn't in the fixtures (T2M 0L4): the fixture sharing its forward sortation area (T2M), else
     * its first two characters (T2 = Calgary, T5 = Edmonton), so any real code near a fixture city resolves to that
     * city's area. The code is kept as typed.
     */
    private static Optional<PlaceParts> sameCanadianArea(String code, @Nullable String country) {
        var typed = compact(code);
        if ((country != null && !"CA".equalsIgnoreCase(country)) || !CANADIAN_POSTAL.matcher(typed).matches()) {
            return Optional.empty();
        }
        var canadian = FIXTURES.stream().filter(p -> p.postalCode() != null).toList();
        return canadian.stream()
                .filter(p -> compact(p.postalCode()).startsWith(typed.substring(0, 3)))
                .findFirst()
                .or(() -> canadian.stream()
                        .filter(p -> compact(p.postalCode()).startsWith(typed.substring(0, 2)))
                        .findFirst())
                .map(p -> new PlaceParts(
                        "postal:CA-" + typed,
                        "%s, %s %s".formatted(p.city(), p.province(), formatCanadian(typed)),
                        null,
                        null,
                        null,
                        p.city(),
                        p.province(),
                        formatCanadian(typed),
                        "CA",
                        p.point()));
    }

    private static String formatCanadian(String compact) {
        return compact.length() == 6 ? compact.substring(0, 3) + " " + compact.substring(3) : compact;
    }

    private static Optional<PlaceParts> exactOrPrefix(String code, @Nullable String country) {
        var typed = compact(code);
        return java.util.stream.Stream.concat(FIXTURES.stream(), POSTAL_ABROAD.stream())
                .filter(p -> p.postalCode() != null)
                .filter(p -> country == null || country.equalsIgnoreCase(p.country()))
                .filter(p -> compact(p.postalCode()).equals(typed)
                        || (typed.length() >= 3 && compact(p.postalCode()).startsWith(typed)))
                .min(Comparator.comparing(
                        (PlaceParts p) -> !compact(p.postalCode()).equals(typed)))
                .map(p -> new PlaceParts(
                        "postal:" + p.country() + "-" + compact(p.postalCode()),
                        "%s, %s %s".formatted(p.city(), p.province(), p.postalCode()),
                        null,
                        null,
                        null,
                        p.city(),
                        p.province(),
                        compact(p.postalCode()).equals(typed) ? p.postalCode() : code.strip(),
                        p.country(),
                        p.point()));
    }

    private static String compact(@Nullable String postal) {
        return postal == null ? "" : postal.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
    }

    private static String secondary(PlaceParts p) {
        return "%s, %s %s, Canada".formatted(p.city(), p.province(), p.postalCode());
    }

    private static boolean matches(PlaceParts p, String[] words) {
        var hay = List.of(fold(p.formatted()).split("[^a-z0-9]+"));
        for (var w : words) {
            if (!w.isEmpty() && hay.stream().noneMatch(h -> h.startsWith(w))) {
                return false;
            }
        }
        return true;
    }

    private static String fold(String s) {
        return Normalizer.normalize(s, Normalizer.Form.NFD)
                .replaceAll("\\p{M}", "")
                .toLowerCase(Locale.ROOT);
    }
}
