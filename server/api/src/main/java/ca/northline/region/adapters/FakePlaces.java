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

/**
 * {@code PLACES_PROVIDER=local}: a fixed list of Canadian addresses — design 06's four "1204 17 …" suggestions in
 * Calgary, plus one per other market and a few outside them (Red Deer pilot, Lethbridge waitlist, Toronto, Montréal,
 * Vancouver) so every branch of the Location screen can be tried without a Google key. Matching: every word typed
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

    static final List<PlaceParts> FIXTURES = List.of(
            place("-yyc-beltline", "1204", "17 Ave SW", "Beltline", "Calgary", "AB", "T2T 0B7", 51.0379, -114.0898),
            place(
                    "-yyc-capitol-hill",
                    "1204",
                    "17 Ave NW",
                    "Capitol Hill",
                    "Calgary",
                    "AB",
                    "T2M 0P8",
                    51.0712,
                    -114.0921),
            place("-yyc-sunalta", "1204", "17 St SW", "Sunalta", "Calgary", "AB", "T3C 1A1", 51.0417, -114.1043),
            place("-yyc-inglewood", "1204", "17 Ave SE", "Inglewood", "Calgary", "AB", "T2G 1J6", 51.0376, -114.0290),
            place(
                    "-yyc-forest-lawn",
                    "3715",
                    "17 Ave SE",
                    "Forest Lawn",
                    "Calgary",
                    "AB",
                    "T2A 0S1",
                    51.0381,
                    -113.9858),
            place(
                    "-yyc-downtown",
                    "220",
                    "8 Ave SW",
                    "Downtown Commercial Core",
                    "Calgary",
                    "AB",
                    "T2P 1B5",
                    51.0461,
                    -114.0661),
            place("-yeg-downtown", "10205", "101 St NW", "Downtown", "Edmonton", "AB", "T5J 4H5", 53.5436, -113.4930),
            place("-airdrie", "1204", "Main St S", "Old Town", "Airdrie", "AB", "T4B 3G5", 51.2881, -114.0140),
            place("-red-deer", "4914", "48 Ave", "Downtown", "Red Deer", "AB", "T4N 3T3", 52.2681, -113.8112),
            place("-lethbridge", "910", "4 Ave S", "Downtown", "Lethbridge", "AB", "T1J 0P6", 49.6936, -112.8401),
            place("-toronto", "1204", "Queen St W", "Parkdale", "Toronto", "ON", "M6J 1J6", 43.6426, -79.4295),
            place(
                    "-montreal",
                    "1204",
                    "Rue Sainte-Catherine O",
                    "Ville-Marie",
                    "Montréal",
                    "QC",
                    "H3B 1K1",
                    45.4987,
                    -73.5710),
            place("-vancouver", "1204", "Granville St", "Downtown", "Vancouver", "BC", "V6Z 1M1", 49.2769, -123.1270));

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
