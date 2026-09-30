package ca.northline.merchants.integration;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;

import ca.northline.merchants.domain.RegistryCheck.Answer;
import ca.northline.merchants.domain.RegistryQuery;
import ca.northline.merchants.domain.RegistryRecord.Standing;
import ca.northline.merchants.domain.RegistrySource;
import ca.northline.merchants.domain.RegistrySubject;
import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.LocalDate;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * S-23 registry adapters against WireMock stand-ins built from each source's documentation (docs/runbooks/registries.md).
 * None has run against the live service (no accounts); keys and tokens are obviously fake.
 */
class RegistryAdaptersWireMockTest {

    static WireMockServer server;

    @BeforeAll
    static void start() {
        server = new WireMockServer(wireMockConfig().dynamicPort());
        server.start();
    }

    @AfterAll
    static void stop() {
        server.stop();
    }

    @BeforeEach
    void reset() {
        server.resetAll();
    }

    static RegistryQuery query(RegistrySource source, String number, String... names) {
        return new RegistryQuery(source, RegistrySubject.CORPORATION, null, number, List.of(names));
    }

    // ── Corporations Canada ──────────────────────────────────────────────────────────────────────────────────────

    CorporationsCanadaRegistry corporationsCanada() {
        return new CorporationsCanadaRegistry(
                RegistryHttp.client(
                        CorporationsCanadaRegistry.Api.class,
                        server.baseUrl() + "/ised",
                        h -> h.set("user-key", "fake-ised-key-for-tests")),
                "https://ised-isde.canada.ca/cc/lgcy/fdrlCrpDtls.html?corpId=");
    }

    @Test
    void corporationsCanada_readsTheCurrentNameAndStatus_withTheKeyHeader() {
        server.stubFor(get(urlPathEqualTo("/ised/corporations/1234567.json")).willReturn(okJson("""
                        [{"corporationId":"1234567","act":{"code":"CBCA"},"status":{"code":"1","text":"Active"},
                          "corporationNames":[{"CorporationName":{"name":"Old Name Inc.","current":false}},
                                              {"CorporationName":{"name":"Northern Lights Bakery Inc.","current":true}}],
                          "businessNumbers":{"businessNumber":"123456789"}}]""")));

        var answer = corporationsCanada().lookup(query(RegistrySource.CORPORATIONS_CANADA, "123456-7"));

        assertThat(answer).isInstanceOfSatisfying(Answer.Found.class, f -> {
            assertThat(f.record().name()).isEqualTo("Northern Lights Bakery Inc.");
            assertThat(f.record().number()).isEqualTo("1234567");
            assertThat(f.record().standing()).isEqualTo(Standing.ACTIVE);
            assertThat(f.reference()).endsWith("corpId=1234567");
        });
        server.verify(getRequestedFor(urlPathEqualTo("/ised/corporations/1234567.json"))
                .withHeader("user-key", equalTo("fake-ised-key-for-tests"))
                .withQueryParam("lang", equalTo("eng")));
    }

    @Test
    void corporationsCanada_dissolved_notFound_andDown() {
        server.stubFor(
                get(urlPathEqualTo("/ised/corporations/1111118.json"))
                        .willReturn(
                                okJson(
                                        "{\"corporationId\":\"1111118\",\"name\":\"Bow River Trading Ltd.\",\"status\":\"Dissolved\"}")));
        server.stubFor(get(urlPathEqualTo("/ised/corporations/7777777.json"))
                .willReturn(aResponse().withStatus(404)));
        server.stubFor(get(urlPathEqualTo("/ised/corporations/2222222.json")).willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo("/ised/corporations/5555555.json"))
                .willReturn(aResponse().withStatus(503)));
        var registry = corporationsCanada();

        assertThat(registry.lookup(query(RegistrySource.CORPORATIONS_CANADA, "1111118")))
                .isInstanceOfSatisfying(
                        Answer.Found.class,
                        f -> assertThat(f.record().standing()).isEqualTo(Standing.INACTIVE));
        assertThat(registry.lookup(query(RegistrySource.CORPORATIONS_CANADA, "7777777")))
                .isInstanceOf(Answer.NotFound.class);
        assertThat(registry.lookup(query(RegistrySource.CORPORATIONS_CANADA, "2222222")))
                .isInstanceOf(Answer.NotFound.class);
        assertThat(registry.lookup(query(RegistrySource.CORPORATIONS_CANADA, "5555555")))
                .isInstanceOf(Answer.Unavailable.class);
    }

    // ── Alberta through OpenCorporates ───────────────────────────────────────────────────────────────────────────

    OpenCorporatesAlbertaRegistry alberta() {
        return new OpenCorporatesAlbertaRegistry(
                RegistryHttp.client(OpenCorporatesAlbertaRegistry.Api.class, server.baseUrl(), _ -> {}),
                "fake-opencorporates-token");
    }

    @Test
    void alberta_readsTheCompany_andItsStanding() {
        server.stubFor(get(urlPathEqualTo("/v0.4/companies/ca_ab/2201456789")).willReturn(okJson("""
                        {"api_version":"0.4","results":{"company":{"name":"2201456 ALBERTA LTD.",
                          "company_number":"2201456789","jurisdiction_code":"ca_ab","current_status":"Active",
                          "inactive":false,"opencorporates_url":"https://opencorporates.com/companies/ca_ab/2201456789"}}}""")));
        server.stubFor(get(urlPathEqualTo("/v0.4/companies/ca_ab/2011111111")).willReturn(okJson("""
                        {"results":{"company":{"name":"Sunalta Snow Removal Ltd.","company_number":"2011111111",
                          "current_status":"Struck","inactive":true}}}""")));
        server.stubFor(get(urlPathEqualTo("/v0.4/companies/ca_ab/404"))
                .willReturn(aResponse().withStatus(404)));
        var registry = alberta();

        assertThat(registry.lookup(query(RegistrySource.ALBERTA_CORPORATE_REGISTRY, "2201456789")))
                .isInstanceOfSatisfying(Answer.Found.class, f -> {
                    assertThat(f.record().standing()).isEqualTo(Standing.ACTIVE);
                    assertThat(f.reference()).isEqualTo("https://opencorporates.com/companies/ca_ab/2201456789");
                });
        assertThat(registry.lookup(query(RegistrySource.ALBERTA_CORPORATE_REGISTRY, "2011111111")))
                .isInstanceOfSatisfying(
                        Answer.Found.class,
                        f -> assertThat(f.record().standing()).isEqualTo(Standing.INACTIVE));
        assertThat(registry.lookup(query(RegistrySource.ALBERTA_CORPORATE_REGISTRY, "404")))
                .isInstanceOf(Answer.NotFound.class);
        server.verify(getRequestedFor(urlPathEqualTo("/v0.4/companies/ca_ab/2201456789"))
                .withQueryParam("api_token", equalTo("fake-opencorporates-token")));
    }

    // ── City of Calgary (Socrata) ────────────────────────────────────────────────────────────────────────────────

    CalgaryBusinessLicences calgary() {
        return new CalgaryBusinessLicences(
                RegistryHttp.client(
                        CalgaryBusinessLicences.Api.class,
                        server.baseUrl(),
                        h -> h.set("X-App-Token", "fake-socrata-app-token")),
                "vdjc-pybd",
                "https://data.calgary.ca");
    }

    @Test
    void calgary_latestRenewalWins_andTheAppTokenIsSent() {
        server.stubFor(get(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withQueryParam("getbusid", equalTo("BL 22-118840"))
                .willReturn(okJson("""
                        [{"getbusid":"BL 22-118840","tradename":"PHO DAU BO","licencetypes":"FOOD SERVICE - PREMISES",
                          "jobstatusdesc":"EXPIRED","exp_dt":"2025-05-31T00:00:00.000","comdistnm":"FOREST LAWN"},
                         {"getbusid":"BL 22-118840","tradename":"PHO DAU BO","licencetypes":"FOOD SERVICE - PREMISES",
                          "jobstatusdesc":"RENEWAL LICENSED","exp_dt":"2027-05-31T00:00:00.000","comdistnm":"FOREST LAWN"}]""")));

        var answer = calgary().lookup(query(RegistrySource.CALGARY_BUSINESS_LICENCES, "BL 22-118840"));

        assertThat(answer).isInstanceOfSatisfying(Answer.Found.class, f -> {
            assertThat(f.record().name()).isEqualTo("PHO DAU BO");
            assertThat(f.record().standing()).isEqualTo(Standing.ACTIVE);
            assertThat(f.record().rawStatus()).isEqualTo("RENEWAL LICENSED");
            assertThat(f.record().expiresOn()).isEqualTo(LocalDate.of(2027, 5, 31));
            assertThat(f.reference()).startsWith("https://data.calgary.ca/resource/vdjc-pybd.json?getbusid=");
        });
        server.verify(getRequestedFor(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withHeader("X-App-Token", equalTo("fake-socrata-app-token")));
    }

    @Test
    void calgary_triesWithoutTheBlPrefix_thenNotFound() {
        server.stubFor(get(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withQueryParam("getbusid", equalTo("BL 300"))
                .willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withQueryParam("getbusid", equalTo("300"))
                .willReturn(okJson("[{\"getbusid\":\"300\",\"tradename\":\"X\",\"jobstatusdesc\":\"LICENSED\"}]")));
        server.stubFor(get(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withQueryParam("getbusid", equalTo("999"))
                .willReturn(okJson("[]")));
        server.stubFor(get(urlPathEqualTo("/resource/vdjc-pybd.json"))
                .withQueryParam("getbusid", equalTo("429"))
                .willReturn(aResponse().withStatus(429)));
        var registry = calgary();

        assertThat(registry.lookup(query(RegistrySource.CALGARY_BUSINESS_LICENCES, "BL 300")))
                .isInstanceOfSatisfying(
                        Answer.Found.class, f -> assertThat(f.record().number()).isEqualTo("300"));
        assertThat(registry.lookup(query(RegistrySource.CALGARY_BUSINESS_LICENCES, "999")))
                .isInstanceOf(Answer.NotFound.class);
        assertThat(registry.lookup(query(RegistrySource.CALGARY_BUSINESS_LICENCES, "429")))
                .isInstanceOf(Answer.Unavailable.class);
    }
}
