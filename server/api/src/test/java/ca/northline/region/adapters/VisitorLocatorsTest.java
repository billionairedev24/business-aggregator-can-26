package ca.northline.region.adapters;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import ca.northline.region.application.VisitorLocator.VisitorPlace;
import ca.northline.region.application.VisitorLocator.VisitorRequest;
import java.io.IOException;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import org.junit.jupiter.api.Test;

/** S-192: the pluggable visitor detectors (edge headers, GeoIP database, none) and their configuration. */
class VisitorLocatorsTest {

    private static VisitorRequest headers(Map<String, String> h, String address) {
        var ci = new TreeMap<String, String>(String.CASE_INSENSITIVE_ORDER);
        ci.putAll(h);
        return new VisitorRequest(ci::get, address);
    }

    @Test
    void cloudflaresVisitorLocationHeaders() {
        var found = new EdgeHeaderLocator()
                .locate(headers(
                        Map.of(
                                "CF-IPCountry", "US",
                                "cf-ipcity", "San Francisco",
                                "cf-region-code", "CA",
                                "cf-region", "California",
                                "cf-postal-code", "94107",
                                "cf-iplatitude", "37.7697",
                                "cf-iplongitude", "-122.3933"),
                        "203.0.113.9"));
        assertThat(found).contains(new VisitorPlace("US", "CA", "San Francisco", "94107", 37.7697, -122.3933, "edge"));
    }

    @Test
    void theCountryAloneWithoutTheManagedTransform_andTheRegionNameWithoutItsCode() {
        assertThat(new EdgeHeaderLocator().locate(headers(Map.of("cf-ipcountry", "ca"), "203.0.113.9")))
                .contains(new VisitorPlace("CA", null, null, null, null, null, "edge"));
        assertThat(new EdgeHeaderLocator()
                        .locate(headers(Map.of("cf-ipcountry", "FR", "cf-region", "Île-de-France"), "203.0.113.9"))
                        .map(VisitorPlace::region))
                .contains("Île-de-France");
    }

    @Test
    void genericXGeoHeadersWhenCloudflareSaysNothing_percentEncodedNamesDecoded() {
        var found = new EdgeHeaderLocator()
                .locate(headers(
                        Map.of("X-Geo-Country", "CA", "X-Geo-City", "Trois-Rivi%C3%A8res", "X-Geo-Postal-Code", "G9A"),
                        "203.0.113.9"));
        assertThat(found).contains(new VisitorPlace("CA", null, "Trois-Rivières", "G9A", null, null, "edge"));
    }

    @Test
    void utf8HeaderBytesReadAsLatin1AreReadAgain() {
        var raw = new String("Montréal".getBytes(java.nio.charset.StandardCharsets.UTF_8), java.nio.charset.StandardCharsets.ISO_8859_1);
        assertThat(new EdgeHeaderLocator()
                        .locate(headers(Map.of("cf-ipcountry", "CA", "cf-ipcity", raw), "203.0.113.9"))
                        .map(VisitorPlace::city))
                .contains("Montréal");
        assertThat(VisitorParts.utf8("Montréal")).isEqualTo("Montréal");
        assertThat(VisitorParts.utf8("Zürich")).isEqualTo("Zürich");
    }

    @Test
    void malformedPartsAreDropped_nothingLeftIsNoGuess() {
        var e = new EdgeHeaderLocator();
        assertThat(e.locate(headers(Map.of("cf-ipcountry", "XX", "cf-ipcity", "<b>x</b>"), "203.0.113.9")))
                .isEmpty();
        assertThat(e.locate(headers(Map.of("cf-ipcountry", "T1"), "203.0.113.9")))
                .isEmpty();
        var odd = e.locate(headers(
                Map.of(
                        "cf-ipcountry", "DE",
                        "cf-ipcity", "x".repeat(61),
                        "cf-postal-code", "10115_",
                        "cf-iplatitude", "95",
                        "cf-iplongitude", "13.4"),
                "203.0.113.9"));
        assertThat(odd).contains(new VisitorPlace("DE", null, null, null, null, null, "edge"));
        assertThat(e.locate(headers(Map.of(), "203.0.113.9"))).isEmpty();
    }

    @Test
    void geoIpMapsTheCitySchema() {
        var record = Map.<String, Object>of(
                "country", Map.of("iso_code", "GB", "names", Map.of("en", "United Kingdom")),
                "subdivisions", List.of(Map.of("iso_code", "ENG", "names", Map.of("en", "England"))),
                "city", Map.of("names", Map.of("en", "London", "fr", "Londres")),
                "postal", Map.of("code", "SW1A"),
                "location", Map.of("latitude", 51.5, "longitude", -0.12, "accuracy_radius", 20));
        var geo = new GeoIpDatabaseLocator(address -> address.getHostAddress().equals("81.2.69.142") ? record : null);
        assertThat(geo.locate(new VisitorRequest(h -> null, "81.2.69.142")))
                .contains(new VisitorPlace("GB", "ENG", "London", "SW1A", 51.5, -0.12, "geoip"));
        assertThat(geo.locate(new VisitorRequest(h -> null, "81.2.69.1"))).isEmpty();
    }

    @Test
    void geoIpSkipsPrivateOrUnparseableAddresses_andSurvivesAReadError() {
        var calls = new int[1];
        var geo = new GeoIpDatabaseLocator(address -> {
            calls[0]++;
            throw new IOException("corrupt");
        });
        for (var address : new String[] {null, "", "10.0.0.4", "127.0.0.1", "192.168.1.2", "fe80::1", "not-an-ip"}) {
            assertThat(geo.locate(new VisitorRequest(h -> null, address)))
                    .as(String.valueOf(address))
                    .isEmpty();
        }
        assertThat(calls[0]).isZero();
        assertThat(geo.locate(new VisitorRequest(h -> null, "203.0.113.9"))).isEmpty();
        assertThat(calls[0]).isOne();
    }

    @Test
    void theDetectorIsChosenByConfiguration() {
        var config = new VisitorLocationConfig();
        assertThat(config.visitorLocator("none", "").name()).isEqualTo("none");
        assertThat(config.visitorLocator("", "").name()).isEqualTo("none");
        assertThat(config.visitorLocator("EDGE", "").name()).isEqualTo("edge");
        assertThat(config.visitorLocator("none", "").locate(headers(Map.of("cf-ipcountry", "CA"), "203.0.113.9")))
                .isEmpty();
        assertThatThrownBy(() -> config.visitorLocator("geoip", "/nowhere/GeoLite2-City.mmdb"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("VISITOR_LOCATION_DATABASE")
                .hasMessageContaining("docs/runbooks/location.md");
        assertThatThrownBy(() -> config.visitorLocator("maxmind", ""))
                .hasMessage("VISITOR_LOCATION_DETECTOR must be none, edge or geoip, not maxmind");
    }

    @Test
    void fakePlacesKnowsPostalCodesOfSeveralCountries() {
        var fake = new FakePlaces();
        assertThat(fake.postalCode("T2T0B7", null, Locale.ENGLISH)).hasValueSatisfying(p -> {
            assertThat(p.city()).isEqualTo("Calgary");
            assertThat(p.postalCode()).isEqualTo("T2T 0B7");
            assertThat(p.street()).doesNotContain("1204");
        });
        assertThat(fake.postalCode("90210", "US", Locale.ENGLISH))
                .hasValueSatisfying(p -> assertThat(p.country()).isEqualTo("US"));
        assertThat(fake.postalCode("90210", "CA", Locale.ENGLISH)).isEmpty();
        assertThat(fake.postalCode("00000", null, Locale.ENGLISH)).isEmpty();
    }
}
