package ca.northline.region.adapters;

import ca.northline.region.application.PlacesAutocomplete;
import ca.northline.region.domain.GeoPoint;
import ca.northline.region.domain.PlaceParts;
import java.net.URI;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientException;
import org.springframework.web.service.annotation.GetExchange;
import org.springframework.web.service.annotation.PostExchange;
import tools.jackson.databind.JsonNode;

/**
 * Google Maps Platform (S-47), written from developers.google.com/maps/documentation; <b>never run against Google</b>
 * (no account exists) — tested with WireMock.
 *
 * <ul>
 *   <li>Autocomplete: Places API (New) {@code POST /v1/places:autocomplete} with {@code includedRegionCodes: ["ca"]},
 *       address types only, the browser's session token, and a 50 km bias around the visitor when known.
 *   <li>Details: {@code GET /v1/places/{placeId}} with the same session token (ends the billed session), field mask
 *       {@code id,formattedAddress,addressComponents,location}.
 *   <li>Reverse: Geocoding API {@code GET /maps/api/geocode/json?latlng=…&result_type=street_address|premise|…}.
 * </ul>
 *
 * The key travels in {@code X-Goog-Api-Key} for Places; the Geocoding API only takes it as {@code key=} in the query, so
 * those errors are rethrown without the request (its URI would carry the key into logs).
 */
@Slf4j
class GooglePlaces implements PlacesAutocomplete {

    static final String AUTOCOMPLETE_MASK = "suggestions.placePrediction.placeId,suggestions.placePrediction.text,"
            + "suggestions.placePrediction.structuredFormat";
    static final String DETAILS_MASK = "id,formattedAddress,addressComponents,location";
    static final List<String> ADDRESS_TYPES = List.of("street_address", "premise", "subpremise", "route");
    static final String REVERSE_TYPES = "street_address|premise|subpremise|route|neighborhood|locality";
    static final double BIAS_METRES = 50_000;

    interface Api {
        @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE, accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode autocomplete(
                URI url,
                @RequestHeader("X-Goog-Api-Key") String key,
                @RequestHeader("X-Goog-FieldMask") String mask,
                @RequestBody Map<String, Object> body);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode details(
                URI url, @RequestHeader("X-Goog-Api-Key") String key, @RequestHeader("X-Goog-FieldMask") String mask);

        @GetExchange(accept = MediaType.APPLICATION_JSON_VALUE)
        JsonNode geocode(URI url);
    }

    private final PlacesProperties config;
    private final Api api;

    GooglePlaces(PlacesProperties config, Api api) {
        this.config = config;
        this.api = api;
    }

    @Override
    public String attribution() {
        return "Google";
    }

    @Override
    public List<Prediction> autocomplete(
            String input, @Nullable String sessionToken, Locale locale, @Nullable GeoPoint near) {
        var body = new LinkedHashMap<String, Object>();
        body.put("input", input);
        if (sessionToken != null) {
            body.put("sessionToken", sessionToken);
        }
        body.put("includedRegionCodes", List.of("ca"));
        body.put("regionCode", "ca");
        body.put("languageCode", language(locale));
        body.put("includedPrimaryTypes", ADDRESS_TYPES);
        if (near != null) {
            body.put(
                    "locationBias",
                    Map.of(
                            "circle",
                            Map.of(
                                    "center",
                                    Map.of("latitude", near.lat(), "longitude", near.lng()),
                                    "radius",
                                    BIAS_METRES)));
        }
        var json = call(
                "autocomplete",
                () -> api.autocomplete(
                        uri(config.placesUrl(), "/v1/places:autocomplete", ""), config.key(), AUTOCOMPLETE_MASK, body));
        var out = new ArrayList<Prediction>();
        for (var s : json.path("suggestions")) {
            var p = s.path("placePrediction");
            var id = text(p, "placeId");
            if (id == null) {
                continue; // a query prediction, not a place
            }
            var main = text(p.path("structuredFormat").path("mainText"), "text");
            var secondary = text(p.path("structuredFormat").path("secondaryText"), "text");
            var full = text(p.path("text"), "text");
            out.add(new Prediction(
                    id, main != null ? main : full != null ? full : id, secondary != null ? secondary : ""));
        }
        return out;
    }

    @Override
    public Optional<PlaceParts> details(String placeId, @Nullable String sessionToken, Locale locale) {
        var query = "?languageCode=" + language(locale) + "&regionCode=ca"
                + (sessionToken == null ? "" : "&sessionToken=" + sessionToken);
        try {
            var json = call(
                    "details",
                    () -> api.details(
                            uri(config.placesUrl(), "/v1/places/" + encode(placeId), query),
                            config.key(),
                            DETAILS_MASK));
            return Optional.ofNullable(parseNew(json, placeId));
        } catch (NotFoundAtGoogle _) {
            return Optional.empty();
        }
    }

    @Override
    public Optional<PlaceParts> reverse(GeoPoint point, Locale locale) {
        var query = "?latlng=" + point.lat() + "," + point.lng() + "&language=" + language(locale) + "&result_type="
                + encode(REVERSE_TYPES) + "&key=" + encode(config.key());
        JsonNode json;
        try {
            json = api.geocode(uri(config.geocodingUrl(), "/maps/api/geocode/json", query));
        } catch (HttpClientErrorException e) {
            throw new Unavailable(
                    "Geocoding API refused the request: " + e.getStatusCode().value(), null);
        } catch (RestClientException e) {
            // the exception names the URI, which carries the key: say what failed, drop the rest
            throw new Unavailable("Geocoding API unavailable: " + e.getClass().getSimpleName(), null);
        }
        var status = json.path("status").asString("");
        return switch (status) {
            case "OK" -> {
                var first = json.path("results").path(0);
                yield Optional.ofNullable(parseLegacy(first, point));
            }
            case "ZERO_RESULTS" -> Optional.empty();
            default -> throw new Unavailable("Geocoding API answered " + status, null);
        };
    }

    /** One call: 404 / 400 → not found (a stale or forged place id), anything else → {@link Unavailable}. */
    private JsonNode call(String what, Supplier<JsonNode> call) {
        try {
            return call.get();
        } catch (HttpClientErrorException.NotFound | HttpClientErrorException.BadRequest e) {
            if (what.equals("details")) {
                throw new NotFoundAtGoogle();
            }
            throw new Unavailable(
                    "Places " + what + " refused: " + e.getStatusCode().value(), e);
        } catch (RestClientException e) {
            log.warn("Places {} failed: {}", what, e.getMessage());
            throw new Unavailable("Places " + what + " unavailable", e);
        }
    }

    private static final class NotFoundAtGoogle extends RuntimeException {
        NotFoundAtGoogle() {
            super(null, null, false, false);
        }
    }

    /** Places API (New): {@code addressComponents[{longText, shortText, types}]}, {@code location{latitude, longitude}}. */
    static @Nullable PlaceParts parseNew(JsonNode json, String placeId) {
        var location = json.path("location");
        if (!location.path("latitude").isNumber() || !location.path("longitude").isNumber()) {
            return null;
        }
        var c = new Components();
        for (var comp : json.path("addressComponents")) {
            c.add(comp.path("types"), text(comp, "longText"), text(comp, "shortText"));
        }
        return c.toParts(
                Optional.ofNullable(text(json, "id")).orElse(placeId),
                Optional.ofNullable(text(json, "formattedAddress")).orElse(""),
                new GeoPoint(
                        location.path("latitude").asDouble(),
                        location.path("longitude").asDouble()));
    }

    /** Geocoding API: {@code address_components[{long_name, short_name, types}]}, {@code geometry.location{lat, lng}}. */
    static @Nullable PlaceParts parseLegacy(JsonNode result, GeoPoint asked) {
        if (result.isMissingNode()) {
            return null;
        }
        var c = new Components();
        for (var comp : result.path("address_components")) {
            c.add(comp.path("types"), text(comp, "long_name"), text(comp, "short_name"));
        }
        var loc = result.path("geometry").path("location");
        var point = loc.path("lat").isNumber()
                ? new GeoPoint(loc.path("lat").asDouble(), loc.path("lng").asDouble())
                : asked;
        return c.toParts(
                Optional.ofNullable(text(result, "place_id")).orElse(""),
                Optional.ofNullable(text(result, "formatted_address")).orElse(""),
                point);
    }

    /** Canada-shaped address parts from typed components (the first of each type wins). */
    private static final class Components {
        private @Nullable String number;
        private @Nullable String route;
        private @Nullable String neighbourhood;
        private @Nullable String city;
        private @Nullable String province;
        private @Nullable String postal;
        private @Nullable String country;

        void add(JsonNode types, @Nullable String longText, @Nullable String shortText) {
            for (var t : types) {
                switch (t.asString("")) {
                    case "street_number" -> number = number == null ? longText : number;
                    case "route" -> route = route == null ? shortText : route;
                    case "neighborhood", "sublocality_level_1", "sublocality" ->
                        neighbourhood = neighbourhood == null ? longText : neighbourhood;
                    case "locality" -> city = city == null ? longText : city;
                    case "administrative_area_level_1" -> province = province == null ? shortText : province;
                    case "postal_code" -> postal = postal == null ? longText : postal;
                    case "country" -> country = country == null ? shortText : country;
                    default -> {
                        // not used
                    }
                }
            }
        }

        PlaceParts toParts(String placeId, String formatted, GeoPoint point) {
            return new PlaceParts(
                    placeId, formatted, number, route, neighbourhood, city, province, postal, country, point);
        }
    }

    private static @Nullable String text(JsonNode node, String field) {
        var v = node.path(field);
        return v.isString() && !v.asString().isBlank() ? v.asString() : null;
    }

    private static String language(Locale locale) {
        return locale.getLanguage().equals("fr") ? "fr" : "en";
    }

    private static String encode(String s) {
        return java.net.URLEncoder.encode(s, java.nio.charset.StandardCharsets.UTF_8)
                .replace("+", "%20");
    }

    private static URI uri(URI base, String path, String query) {
        var b = base.toString();
        return URI.create((b.endsWith("/") ? b.substring(0, b.length() - 1) : b) + path + query);
    }
}
