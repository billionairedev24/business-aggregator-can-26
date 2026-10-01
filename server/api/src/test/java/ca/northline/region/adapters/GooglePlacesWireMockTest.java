package ca.northline.region.adapters;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.region.application.PlacesAutocomplete;
import ca.northline.region.domain.GeoPoint;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.net.URI;
import java.time.Duration;
import java.util.Locale;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/**
 * S-47: the Google Maps Platform adapter against a WireMock stand-in written from Google's documentation (Places API
 * (New) autocomplete and place details, Geocoding API reverse). Never run against Google. The key is obviously fake.
 */
class GooglePlacesWireMockTest {

    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());
    static final String KEY = "AIzaFAKE-northline-test-key";

    static {
        WM.start();
    }

    @AfterAll
    static void stop() {
        WM.stop();
    }

    private GooglePlaces google;

    @BeforeEach
    void setUp() {
        WM.resetAll();
        var p = new PlacesProperties(
                "google", KEY, URI.create(WM.baseUrl()), URI.create(WM.baseUrl()), Duration.ofSeconds(2), 60);
        google = new GooglePlaces(p, PlacesConfig.client(p));
    }

    @Test
    void autocompleteAsksForCanadianAddressesWithTheSessionAndKeyInAHeader() {
        WM.stubFor(post(urlPathEqualTo("/v1/places:autocomplete")).willReturn(okJson("""
                {"suggestions": [
                  {"placePrediction": {"place": "places/ChIJabc", "placeId": "ChIJabc",
                    "text": {"text": "1204 17 Ave SW, Calgary, AB T2T 0B7, Canada"},
                    "structuredFormat": {"mainText": {"text": "1204 17 Ave SW"},
                                         "secondaryText": {"text": "Calgary, AB T2T 0B7, Canada"}}}},
                  {"queryPrediction": {"text": {"text": "1204 17 avenue"}}},
                  {"placePrediction": {"placeId": "ChIJdef", "text": {"text": "1204 17 Ave NW, Calgary, AB, Canada"}}}
                ]}""")));

        var items = google.autocomplete(
                "1204 17 ave",
                "5f2c9a0e-2b7c-4d7e-9a51-0c1e4f3a8b21",
                Locale.CANADA_FRENCH,
                new GeoPoint(51.04, -114.07));

        assertThat(items)
                .containsExactly(
                        new PlacesAutocomplete.Prediction("ChIJabc", "1204 17 Ave SW", "Calgary, AB T2T 0B7, Canada"),
                        new PlacesAutocomplete.Prediction("ChIJdef", "1204 17 Ave NW, Calgary, AB, Canada", ""));
        WM.verify(postRequestedFor(urlPathEqualTo("/v1/places:autocomplete"))
                .withHeader("X-Goog-Api-Key", equalTo(KEY))
                .withHeader("X-Goog-FieldMask", equalTo(GooglePlaces.AUTOCOMPLETE_MASK))
                .withRequestBody(equalToJson("""
                        {"input": "1204 17 ave", "sessionToken": "5f2c9a0e-2b7c-4d7e-9a51-0c1e4f3a8b21",
                         "includedRegionCodes": ["ca"], "regionCode": "ca", "languageCode": "fr",
                         "includedPrimaryTypes": ["street_address", "premise", "subpremise", "route"],
                         "locationBias": {"circle": {"center": {"latitude": 51.04, "longitude": -114.07},
                                                     "radius": 50000.0}}}""")));
        assertThat(WM.getAllServeEvents().getFirst().getRequest().getUrl()).doesNotContain(KEY);
    }

    @Test
    void detailsReadTheAddressComponentsWithTheSameSession() {
        WM.stubFor(get(urlPathEqualTo("/v1/places/ChIJabc")).willReturn(okJson("""
                {"id": "ChIJabc", "formattedAddress": "1204 17 Ave SW, Calgary, AB T2T 0B7, Canada",
                 "location": {"latitude": 51.0379, "longitude": -114.0898},
                 "addressComponents": [
                   {"longText": "1204", "shortText": "1204", "types": ["street_number"]},
                   {"longText": "17 Avenue Southwest", "shortText": "17 Ave SW", "types": ["route"]},
                   {"longText": "Beltline", "shortText": "Beltline", "types": ["neighborhood", "political"]},
                   {"longText": "Calgary", "shortText": "Calgary", "types": ["locality", "political"]},
                   {"longText": "Alberta", "shortText": "AB", "types": ["administrative_area_level_1", "political"]},
                   {"longText": "Canada", "shortText": "CA", "types": ["country", "political"]},
                   {"longText": "T2T 0B7", "shortText": "T2T 0B7", "types": ["postal_code"]}]}""")));

        var place = google.details("ChIJabc", "5f2c9a0e-2b7c", Locale.CANADA).orElseThrow();

        assertThat(place.street()).isEqualTo("1204 17 Ave SW");
        assertThat(place.neighbourhood()).isEqualTo("Beltline");
        assertThat(place.city()).isEqualTo("Calgary");
        assertThat(place.province()).isEqualTo("AB");
        assertThat(place.postalCode()).isEqualTo("T2T 0B7");
        assertThat(place.inCanada()).isTrue();
        assertThat(place.point()).isEqualTo(new GeoPoint(51.0379, -114.0898));
        WM.verify(getRequestedFor(urlPathEqualTo("/v1/places/ChIJabc"))
                .withQueryParam("sessionToken", equalTo("5f2c9a0e-2b7c"))
                .withQueryParam("languageCode", equalTo("en"))
                .withHeader("X-Goog-Api-Key", equalTo(KEY))
                .withHeader("X-Goog-FieldMask", equalTo(GooglePlaces.DETAILS_MASK)));
    }

    @Test
    void anUnknownPlaceIsNotFoundAndOtherFailuresAreUnavailable() {
        WM.stubFor(get(urlPathEqualTo("/v1/places/nope"))
                .willReturn(aResponse()
                        .withStatus(404)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":404,\"status\":\"NOT_FOUND\"}}")));
        assertThat(google.details("nope", null, Locale.CANADA)).isEmpty();

        WM.stubFor(post(urlPathEqualTo("/v1/places:autocomplete"))
                .willReturn(aResponse()
                        .withStatus(403)
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"error\":{\"code\":403,\"status\":\"PERMISSION_DENIED\"}}")));
        assertThatThrownBy(() -> google.autocomplete("1204", null, Locale.CANADA, null))
                .isInstanceOf(PlacesAutocomplete.Unavailable.class);

        WM.stubFor(post(urlPathEqualTo("/v1/places:autocomplete"))
                .willReturn(okJson("{}").withFixedDelay(3_000)));
        assertThatThrownBy(() -> google.autocomplete("1204", null, Locale.CANADA, null))
                .isInstanceOf(PlacesAutocomplete.Unavailable.class);
    }

    @Test
    void reverseGeocodingNamesTheNeighbourhoodAndKeepsTheKeyOutOfErrors() {
        WM.stubFor(get(urlPathEqualTo("/maps/api/geocode/json")).willReturn(okJson("""
                {"status": "OK", "results": [
                  {"place_id": "ChIJxyz", "formatted_address": "220 8 Ave SW, Calgary, AB T2P 1B5, Canada",
                   "geometry": {"location": {"lat": 51.0461, "lng": -114.0661}},
                   "address_components": [
                     {"long_name": "220", "short_name": "220", "types": ["street_number"]},
                     {"long_name": "8 Avenue Southwest", "short_name": "8 Ave SW", "types": ["route"]},
                     {"long_name": "Downtown Commercial Core", "short_name": "Downtown Commercial Core",
                      "types": ["neighborhood", "political"]},
                     {"long_name": "Calgary", "short_name": "Calgary", "types": ["locality", "political"]},
                     {"long_name": "Alberta", "short_name": "AB", "types": ["administrative_area_level_1"]},
                     {"long_name": "Canada", "short_name": "CA", "types": ["country"]}]}]}""")));

        var place =
                google.reverse(new GeoPoint(51.0461, -114.0661), Locale.CANADA).orElseThrow();
        assertThat(place.neighbourhood()).isEqualTo("Downtown Commercial Core");
        assertThat(place.city()).isEqualTo("Calgary");
        WM.verify(getRequestedFor(urlPathEqualTo("/maps/api/geocode/json"))
                .withQueryParam("latlng", equalTo("51.0461,-114.0661"))
                .withQueryParam("key", equalTo(KEY))
                .withQueryParam("result_type", equalTo(GooglePlaces.REVERSE_TYPES)));

        WM.stubFor(get(urlPathEqualTo("/maps/api/geocode/json"))
                .willReturn(okJson("{\"status\": \"ZERO_RESULTS\", \"results\": []}")));
        assertThat(google.reverse(new GeoPoint(60, -100), Locale.CANADA)).isEmpty();

        WM.stubFor(get(urlPathEqualTo("/maps/api/geocode/json"))
                .willReturn(okJson(
                        "{\"status\": \"REQUEST_DENIED\", \"error_message\": \"The provided API key is invalid.\"}")));
        assertThatThrownBy(() -> google.reverse(new GeoPoint(51, -114), Locale.CANADA))
                .isInstanceOf(PlacesAutocomplete.Unavailable.class)
                .hasMessageNotContaining(KEY);

        WM.stubFor(get(urlPathEqualTo("/maps/api/geocode/json"))
                .willReturn(aResponse().withStatus(500)));
        assertThatThrownBy(() -> google.reverse(new GeoPoint(51, -114), Locale.CANADA))
                .isInstanceOf(PlacesAutocomplete.Unavailable.class)
                .hasMessageNotContaining(KEY)
                .hasNoCause();
    }

    @Test
    void theProviderIsChosenByConfiguration() {
        var config = new PlacesConfig();
        var local = new PlacesProperties(
                "local", null, URI.create("https://x"), URI.create("https://x"), Duration.ofSeconds(1), 60);
        assertThat(config.placesAutocomplete(local, new MockEnvironment())).isInstanceOf(FakePlaces.class);

        var staging = new MockEnvironment();
        staging.setActiveProfiles("staging", "cloud");
        assertThatThrownBy(() -> config.placesAutocomplete(local, staging))
                .hasMessageContaining("PLACES_PROVIDER=local is not allowed under staging/prod");

        var noKey = new PlacesProperties(
                "google", " ", URI.create("https://x"), URI.create("https://x"), Duration.ofSeconds(1), 60);
        assertThatThrownBy(() -> config.placesAutocomplete(noKey, staging)).hasMessageContaining("GOOGLE_MAPS_API_KEY");

        var google = new PlacesProperties(
                "google", KEY, URI.create("https://x"), URI.create("https://x"), Duration.ofSeconds(1), 60);
        assertThat(config.placesAutocomplete(google, staging)).isInstanceOf(GooglePlaces.class);

        var typo = new PlacesProperties(
                "mapbox", null, URI.create("https://x"), URI.create("https://x"), Duration.ofSeconds(1), 60);
        assertThatThrownBy(() -> config.placesAutocomplete(typo, staging)).hasMessageContaining("local or google");
    }
}
