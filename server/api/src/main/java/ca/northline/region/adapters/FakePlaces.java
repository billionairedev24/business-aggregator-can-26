package ca.northline.region.adapters;

import ca.northline.region.application.PlacesAutocomplete;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.PlaceParts;
import java.text.Normalizer;
import java.util.Comparator;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.json.JsonMapper;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import org.jspecify.annotations.Nullable;

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
