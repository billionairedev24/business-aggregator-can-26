package ca.northline.availability.application;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.patchRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.shared.Ids;
import ca.northline.shared.crypto.SecretSealer;
import ca.northline.shared.security.MerchantRole;
import ca.northline.support.IntegrationTest;
import ca.northline.support.OperationsFixtures;
import ca.northline.support.TestData.Business;
import ca.northline.support.TestJwt;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Base64;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-32: the Google Calendar and Microsoft Graph adapters, end to end through the api, against WireMock stand-ins built
 * from the providers' documentation (docs/runbooks/calendar-sync.md). Neither has run against the real service: no
 * accounts exist. Client ids, secrets and tokens are obviously fake. Covers the OAuth code + PKCE exchange, token
 * refresh (and Microsoft's rotation), a revoked grant turning into "reconnect", busy blocks, notification verification
 * and dedupe, incremental reads, and the write-back lifecycle.
 */
class CalendarProvidersWireMockTest extends IntegrationTest {

    static final ZoneId ZONE = ZoneId.of("America/Edmonton");
    static final WireMockServer WM = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        WM.start();
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry r) {
        r.add("northline.calendar.provider", () -> "oauth");
        r.add("northline.calendar.studio-url", () -> "https://studio.test.northline.invalid");
        r.add("northline.calendar.notification-url", () -> "https://api.test.northline.invalid");
        r.add("northline.calendar.google.client-id", () -> "test-google-calendar-client.apps.example.invalid");
        r.add("northline.calendar.google.client-secret", () -> "fake-google-calendar-secret-for-tests");
        r.add("northline.calendar.google.auth-url", () -> "https://accounts.example.invalid/o/oauth2/v2/auth");
        r.add("northline.calendar.google.token-url", () -> WM.baseUrl() + "/google-oauth");
        r.add("northline.calendar.google.api-url", () -> WM.baseUrl() + "/google/calendar/v3");
        r.add("northline.calendar.microsoft.client-id", () -> "00000000-test-microsoft-calendar-client");
        r.add("northline.calendar.microsoft.client-secret", () -> "fake-microsoft-calendar-secret-for-tests");
        r.add("northline.calendar.microsoft.login-url", () -> WM.baseUrl() + "/ms-login");
        r.add("northline.calendar.microsoft.graph-url", () -> WM.baseUrl() + "/graph/v1.0");
    }

    @AfterAll
    static void stop() {
        WM.resetAll();
    }

    @Autowired
    JdbcClient jdbc;

    @Autowired
    CalendarSyncService sync;

    @Autowired
    SecretSealer sealer;

    Business biz;
    String tech;
    String run;

    @BeforeEach
    void setUp() {
        biz = data.business(MerchantRole.OWNER);
        tech = data.user("Jas Gill");
        data.member(biz.merchantId(), tech, MerchantRole.TECHNICIAN);
        run = Ids.next().toLowerCase(Locale.ROOT);
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private String path(String rest) {
        return "/api/v1/merchants/" + biz.merchantId() + "/availability" + rest;
    }

    static Map<String, String> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().toSingleValueMap().entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey, e -> URLDecoder.decode(e.getValue(), StandardCharsets.UTF_8)));
    }

    static String idToken(String claims) {
        var enc = Base64.getUrlEncoder().withoutPadding();
        return enc.encodeToString("{\"alg\":\"RS256\"}".getBytes(StandardCharsets.UTF_8)) + "."
                + enc.encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".fake-signature";
    }

    static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(java.security.MessageDigest.getInstance("SHA-256")
                        .digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    /** "Connect" and return the consent page's parameters. */
    private Map<String, String> startConnect(String provider) throws Exception {
        String url = JsonPath.read(
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        path("/calendars/" + provider))
                                .with(TestJwt.member(tech)))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.authorizationUrl").isNotEmpty())
                        .andReturn()
                        .getResponse()
                        .getContentAsString(),
                "$.authorizationUrl");
        var params = new java.util.HashMap<>(query(url));
        params.put("_url", url);
        return params;
    }

    private void callback(String provider, String code, String state, String expectedLocation) throws Exception {
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                "/api/v1/calendar/oauth/" + provider + "/callback")
                        .param("code", code)
                        .param("state", state)
                        .with(TestJwt.member(tech)))
                .andExpect(status().isSeeOther())
                .andExpect(header().string("Location", expectedLocation));
    }

    private String linkId(String provider) {
        return jdbc.sql(
                        "select id from availability.calendar_links where merchant_id = ? and member_user_id = ? and provider = ?")
                .params(biz.merchantId(), tech, provider)
                .query(String.class)
                .single();
    }

    /** The work queued by the callback (first read, channel, write-back) has finished. */
    private void awaitQuiet(String linkId) {
        await().atMost(Duration.ofSeconds(20))
                .untilAsserted(() -> assertThat(
                                jdbc.sql("select count(*) from events.event_publication where serialized_event like ?")
                                        .params("%" + linkId + "%")
                                        .query(Integer.class)
                                        .single())
                        .isZero());
    }

    private List<String> busyIds(String linkId) {
        return jdbc.sql("select external_event_id from availability.calendar_busy_blocks where link_id = ? order by 1")
                .params(linkId)
                .query(String.class)
                .list();
    }

    private String state(String linkId) {
        return jdbc.sql("select state from availability.calendar_links where id = ?")
                .params(linkId)
                .query(String.class)
                .single();
    }

    static String at(LocalDate day, int hour) {
        return day.atTime(hour, 0).atZone(ZONE).toOffsetDateTime().toString();
    }

    // ── Google ───────────────────────────────────────────────────────────────────

    @Nested
    class Google {

        String access;
        String refresh;

        /** Stubs the code exchange and the first read, connects, and returns the link id. */
        String connect(int expiresIn, String firstAccessToken) throws Exception {
            access = firstAccessToken;
            refresh = "1//fake-refresh-" + run;
            var consent = startConnect("google");
            assertThat(consent.get("_url")).startsWith("https://accounts.example.invalid/o/oauth2/v2/auth?");
            assertThat(consent.get("client_id")).isEqualTo("test-google-calendar-client.apps.example.invalid");
            assertThat(consent.get("redirect_uri"))
                    .isEqualTo("https://studio.test.northline.invalid/api/v1/calendar/oauth/google/callback");
            assertThat(consent.get("scope"))
                    .isEqualTo("openid email https://www.googleapis.com/auth/calendar.events.owned")
                    .doesNotContain("calendarlist");
            assertThat(consent)
                    .containsEntry("access_type", "offline")
                    .containsEntry("include_granted_scopes", "true")
                    .containsEntry("code_challenge_method", "S256")
                    .containsEntry("response_type", "code");

            var code = "4/fake-code-" + run;
            WM.stubFor(post(urlPathEqualTo("/google-oauth/token"))
                    .withFormParam("grant_type", equalTo("authorization_code"))
                    .withFormParam("code", equalTo(code))
                    .willReturn(okJson("""
                            {"access_token":"%s","expires_in":%d,"refresh_token":"%s","token_type":"Bearer",
                             "scope":"openid https://www.googleapis.com/auth/userinfo.email https://www.googleapis.com/auth/calendar.events.owned",
                             "id_token":"%s"}""".formatted(
                            access,
                            expiresIn,
                            refresh,
                            idToken("{\"sub\":\"google-sub-" + run + "\",\"email\":\"jas@prairiewrench.ca\"}")))));
            var tomorrow = LocalDate.now(ZONE).plusDays(1);
            WM.stubFor(get(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                    .withQueryParam("syncToken", absent())
                    .withQueryParam("singleEvents", equalTo("true"))
                    .withHeader(
                            "Authorization", equalTo("Bearer " + (expiresIn > 0 ? access : "ya29.refreshed-" + run)))
                    .willReturn(okJson("""
                            {"items":[
                              {"id":"busy-%1$s","status":"confirmed","start":{"dateTime":"%2$s"},"end":{"dateTime":"%3$s"},
                               "summary":"Dentist","attendees":[{"email":"x@example.invalid","responseStatus":"accepted"}]},
                              {"id":"free-%1$s","status":"confirmed","transparency":"transparent",
                               "start":{"dateTime":"%2$s"},"end":{"dateTime":"%3$s"}},
                              {"id":"declined-%1$s","status":"confirmed","start":{"dateTime":"%2$s"},"end":{"dateTime":"%3$s"},
                               "attendees":[{"self":true,"responseStatus":"declined"}]},
                              {"id":"ours-%1$s","status":"confirmed","start":{"dateTime":"%2$s"},"end":{"dateTime":"%3$s"},
                               "extendedProperties":{"private":{"northlineBookingId":"01JABC"}}},
                              {"id":"cancelled-%1$s","status":"cancelled"}
                            ],"nextSyncToken":"sync-1-%1$s"}""".formatted(run, at(tomorrow, 10), at(tomorrow, 11)))));
            WM.stubFor(post(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/watch"))
                    .willReturn(okJson("""
                            {"kind":"api#channel","resourceId":"res-%s","expiration":"%d"}""".formatted(
                                    run, Instant.now().plus(Duration.ofDays(6)).toEpochMilli()))));

            callback(
                    "google",
                    code,
                    consent.get("state"),
                    "/b/" + biz.merchantId() + "/availability?calendar=google&result=connected");

            // PKCE: the verifier sent to the token endpoint hashes to the challenge sent to the consent page
            var exchange = WM.findAll(postRequestedFor(urlPathEqualTo("/google-oauth/token"))
                            .withRequestBody(containing(form(code))))
                    .getFirst()
                    .getBodyAsString();
            var verifier =
                    URLDecoder.decode(exchange.replaceAll(".*code_verifier=([^&]+).*", "$1"), StandardCharsets.UTF_8);
            assertThat(challenge(verifier)).isEqualTo(consent.get("code_challenge"));
            assertThat(exchange).contains("client_secret=fake-google-calendar-secret-for-tests");

            var link = linkId("google");
            awaitQuiet(link);
            return link;
        }

        @Test
        void connect_readsBusyTimesOnly_opensAChannel_andNotificationsAreVerifiedAndDeduplicated() throws Exception {
            var link = connect(3600, "ya29.fake-access-" + run);
            assertThat(busyIds(link)).containsExactly("busy-" + run);
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path("/sync"))
                            .with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.calendars[0].accountLabel").value("jas@prairiewrench.ca"))
                    .andExpect(jsonPath("$.calendars[0].state").value("connected"))
                    .andExpect(jsonPath("$.calendars[0].lastSyncAt").isNotEmpty());

            var watch = WM.findAll(
                            postRequestedFor(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/watch"))
                                    .withHeader("Authorization", equalTo("Bearer " + access)))
                    .getFirst()
                    .getBodyAsString();
            String channel = JsonPath.read(watch, "$.id");
            String secret = JsonPath.read(watch, "$.token");
            assertThat((String) JsonPath.read(watch, "$.address"))
                    .isEqualTo("https://api.test.northline.invalid/api/v1/webhooks/calendar/google");
            assertThat(jdbc.sql("select secret_hash from availability.calendar_channels where id = ?")
                            .params(channel)
                            .query(String.class)
                            .single())
                    .as("only a hash of the channel secret is stored")
                    .isNotEqualTo(secret)
                    .hasSize(64);

            WM.stubFor(get(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                    .withQueryParam("syncToken", equalTo("sync-1-" + run))
                    .willReturn(okJson("""
                            {"items":[{"id":"busy-%1$s","status":"cancelled"},
                                      {"id":"new-%1$s","start":{"dateTime":"%2$s"},"end":{"dateTime":"%3$s"}}],
                             "nextSyncToken":"sync-2-%1$s"}""".formatted(
                                    run,
                                    at(LocalDate.now(ZONE).plusDays(2), 9),
                                    at(LocalDate.now(ZONE).plusDays(2), 10)))));

            var push = org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            "/api/v1/webhooks/calendar/google")
                    .header("X-Goog-Channel-ID", channel)
                    .header("X-Goog-Channel-Token", secret)
                    .header("X-Goog-Resource-ID", "res-" + run)
                    .header("X-Goog-Resource-State", "exists")
                    .header("X-Goog-Message-Number", "2");
            mvc.perform(push)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(false));
            mvc.perform(push)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.duplicate").value(true));
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(busyIds(link)).containsExactly("new-" + run));

            // forged or misrouted deliveries
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/webhooks/calendar/google")
                            .header("X-Goog-Channel-ID", channel)
                            .header("X-Goog-Channel-Token", secret + "x")
                            .header("X-Goog-Resource-ID", "res-" + run)
                            .header("X-Goog-Message-Number", "3"))
                    .andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/webhooks/calendar/google")
                            .header("X-Goog-Channel-ID", channel)
                            .header("X-Goog-Channel-Token", secret)
                            .header("X-Goog-Resource-ID", "someone-elses-resource")
                            .header("X-Goog-Message-Number", "4"))
                    .andExpect(status().isForbidden());
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/webhooks/calendar/google")
                            .header("X-Goog-Channel-ID", channel)
                            .header("X-Goog-Channel-Token", secret)
                            .header("X-Goog-Resource-ID", "res-" + run)
                            .header("X-Goog-Resource-State", "sync")
                            .header("X-Goog-Message-Number", "1"))
                    .andExpect(status().isOk());

            // an expired sync token (410) reads the calendar again from scratch
            WM.stubFor(get(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                    .withQueryParam("syncToken", equalTo("sync-2-" + run))
                    .willReturn(aResponse()
                            .withStatus(410)
                            .withBody("{\"error\":{\"code\":410,\"message\":\"Sync token is no longer valid\"}}")));
            assertThat(sync.read(link, "primary")).isTrue();
            assertThat(busyIds(link)).containsExactly("busy-" + run);
        }

        @Test
        void expiredAccessToken_isRefreshed_andARevokedGrantAsksForAReconnect() throws Exception {
            // expires_in 0: the first read refreshes before calling the API
            WM.stubFor(post(urlPathEqualTo("/google-oauth/token"))
                    .withFormParam("grant_type", equalTo("refresh_token"))
                    .withFormParam("refresh_token", equalTo("1//fake-refresh-" + run))
                    .willReturn(
                            okJson("{\"access_token\":\"ya29.refreshed-%s\",\"expires_in\":0,\"token_type\":\"Bearer\"}"
                                    .formatted(run))));
            var link = connect(0, "ya29.expired-" + run);
            assertThat(busyIds(link)).containsExactly("busy-" + run);
            WM.verify(postRequestedFor(urlPathEqualTo("/google-oauth/token"))
                    .withRequestBody(containing("grant_type=refresh_token"))
                    .withRequestBody(containing("client_id=test-google-calendar-client.apps.example.invalid")));

            // the member removes Northline in their Google account: the next refresh is refused
            WM.stubFor(post(urlPathEqualTo("/google-oauth/token"))
                    .withFormParam("grant_type", equalTo("refresh_token"))
                    .withFormParam("refresh_token", equalTo("1//fake-refresh-" + run))
                    .atPriority(1)
                    .willReturn(aResponse()
                            .withStatus(400)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                            {"error":"invalid_grant","error_description":"Token has been expired or revoked."}""")));
            assertThat(sync.read(link, "primary")).isFalse();
            assertThat(state(link)).isEqualTo("reconnect");
            assertThat(sync.writeBack(link)).isZero();

            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path("/sync"))
                            .with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.calendars[0].connected").value(true))
                    .andExpect(jsonPath("$.calendars[0].state").value("reconnect"));
            // "Reconnect" starts OAuth again for the same link
            var again = startConnect("google");
            assertThat(again.get("scope")).contains("calendar.events.owned");
            assertThat(busyIds(link)).as("last known busy times keep blocking").containsExactly("busy-" + run);
        }

        @Test
        void writeBack_createsUpdatesAndDeletes_withFirstNameServiceAndAddressOnly() throws Exception {
            var link = connect(3600, "ya29.fake-access-" + run);
            var customer = data.user("Amara Osei");
            var job = new OperationsFixtures(jdbc)
                    .job(biz.merchantId(), tech, customer, Instant.now().plus(Duration.ofDays(3)), "confirmed");
            WM.stubFor(post(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                    .withQueryParam("sendUpdates", equalTo("none"))
                    .withRequestBody(matchingJsonPath("$.extendedProperties.private.northlineBookingId", equalTo(job)))
                    .willReturn(okJson("{\"id\":\"gevt-" + run + "\",\"status\":\"confirmed\"}")));
            WM.stubFor(WireMock.patch(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/gevt-" + run))
                    .willReturn(okJson("{\"id\":\"gevt-" + run + "\"}")));
            WM.stubFor(WireMock.delete(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/gevt-" + run))
                    .willReturn(aResponse().withStatus(204)));

            assertThat(sync.writeBack(link)).isEqualTo(1);
            assertThat(sync.writeBack(link))
                    .as("nothing changed, nothing written")
                    .isZero();
            var created = WM.findAll(postRequestedFor(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                            .withRequestBody(containing(job)))
                    .getFirst()
                    .getBodyAsString();
            assertThat((String) JsonPath.read(created, "$.summary")).isEqualTo("Brake inspection · Amara");
            assertThat((String) JsonPath.read(created, "$.location")).isEqualTo("1204 17 Ave SW");
            assertThat((String) JsonPath.read(created, "$.transparency")).isEqualTo("opaque");
            assertThat(created)
                    .as("no customer PII beyond the first name and the address")
                    .doesNotContain("Osei", "Honda", "P2 stall", "Grinding", customer);

            // reschedule
            jdbc.sql(
                            "update booking.bookings set starts_at = starts_at + interval '1 hour', ends_at = ends_at + interval '1 hour' where id = ?")
                    .params(job)
                    .update();
            assertThat(sync.writeBack(link)).isEqualTo(1);
            WM.verify(patchRequestedFor(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/gevt-" + run))
                    .withRequestBody(matchingJsonPath("$.status", equalTo("confirmed"))));

            // cancel
            jdbc.sql("update booking.bookings set state = 'cancelled' where id = ?")
                    .params(job)
                    .update();
            assertThat(sync.writeBack(link)).isEqualTo(1);
            WM.verify(deleteRequestedFor(urlPathEqualTo("/google/calendar/v3/calendars/primary/events/gevt-" + run))
                    .withQueryParam("sendUpdates", equalTo("none")));
            assertThat(jdbc.sql("select count(*) from availability.calendar_event_mirrors where link_id = ?")
                            .params(link)
                            .query(Integer.class)
                            .single())
                    .isZero();
        }

        @Test
        void chooseCalendars_asksForTheCalendarListIncrementally() throws Exception {
            var link = connect(3600, "ya29.fake-access-" + run);
            String url = JsonPath.read(
                    mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                            path("/calendars/google/sources"))
                                    .with(TestJwt.member(tech)))
                            .andExpect(status().isOk())
                            .andExpect(jsonPath("$.items").isEmpty())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.authorizationUrl");
            var consent = query(url);
            assertThat(consent.get("scope")).contains("calendar.calendarlist.readonly", "calendar.events.owned");
            assertThat(consent).containsEntry("include_granted_scopes", "true");

            WM.stubFor(get(urlPathEqualTo("/google/calendar/v3/calendars/primary/events"))
                    .withQueryParam("syncToken", equalTo("sync-1-" + run))
                    .willReturn(okJson("{\"items\":[],\"nextSyncToken\":\"sync-2-" + run + "\"}")));
            var code = "4/fake-code-list-" + run;
            WM.stubFor(post(urlPathEqualTo("/google-oauth/token"))
                    .withFormParam("code", equalTo(code))
                    .willReturn(okJson("""
                            {"access_token":"%s","expires_in":3600,"refresh_token":"1//fake-refresh-list-%s",
                             "scope":"openid email https://www.googleapis.com/auth/calendar.events.owned https://www.googleapis.com/auth/calendar.calendarlist.readonly",
                             "id_token":"%s"}""".formatted(
                            access,
                            run,
                            idToken("{\"sub\":\"google-sub-" + run + "\",\"email\":\"jas@prairiewrench.ca\"}")))));
            callback(
                    "google",
                    code,
                    consent.get("state"),
                    "/b/" + biz.merchantId() + "/availability?calendar=google&result=connected&choose=1");
            assertThat(linkId("google")).as("same account, same link").isEqualTo(link);
            assertThat(sealer.open(sealedToken(link), link)).isEqualTo("1//fake-refresh-list-" + run);
            awaitQuiet(link);

            WM.stubFor(get(urlPathEqualTo("/google/calendar/v3/users/me/calendarList"))
                    .withQueryParam("minAccessRole", equalTo("owner"))
                    .withHeader("Authorization", equalTo("Bearer " + access))
                    .willReturn(okJson("""
                            {"items":[{"id":"jas@prairiewrench.ca","summary":"jas@prairiewrench.ca","primary":true},
                                      {"id":"family%s@group.calendar.google.com","summary":"Family"}]}""".formatted(run))));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                                    path("/calendars/google/sources"))
                            .with(TestJwt.member(tech)))
                    .andExpect(jsonPath("$.authorizationUrl").doesNotExist())
                    .andExpect(jsonPath("$.items[0].id").value("primary"))
                    .andExpect(jsonPath("$.items[0].selected").value(true))
                    .andExpect(jsonPath("$.items[1].name").value("Family"))
                    .andExpect(jsonPath("$.items[1].selected").value(false));
        }

        @Test
        void disconnect_stopsTheChannel_deletesUpcomingEvents_andRevokesTheGrant() throws Exception {
            var link = connect(3600, "ya29.fake-access-" + run);
            WM.stubFor(post(urlPathEqualTo("/google/calendar/v3/channels/stop"))
                    .willReturn(aResponse().withStatus(204)));
            WM.stubFor(post(urlPathEqualTo("/google-oauth/revoke"))
                    .willReturn(aResponse().withStatus(200)));

            mvc.perform(delete(path("/calendars/google")).with(TestJwt.member(tech)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.connected").value(false));

            WM.verify(postRequestedFor(urlPathEqualTo("/google/calendar/v3/channels/stop"))
                    .withHeader("Authorization", equalTo("Bearer " + access))
                    .withRequestBody(matchingJsonPath("$.resourceId", equalTo("res-" + run))));
            WM.verify(postRequestedFor(urlPathEqualTo("/google-oauth/revoke"))
                    .withRequestBody(equalTo("token=" + form(refresh))));
            assertThat(jdbc.sql("select count(*) from availability.calendar_links where id = ?")
                            .params(link)
                            .query(Integer.class)
                            .single())
                    .isZero();
            assertThat(jdbc.sql("select count(*) from availability.calendar_channels where link_id = ?")
                            .params(link)
                            .query(Integer.class)
                            .single())
                    .isZero();
        }
    }

    private SecretSealer.Sealed sealedToken(String link) {
        return jdbc.sql(
                        "select token_ref, refresh_token_key, refresh_token_enc from availability.calendar_links where id = ?")
                .params(link)
                .query((rs, _) -> new SecretSealer.Sealed(rs.getString(1), rs.getBytes(2), rs.getBytes(3)))
                .single();
    }

    static String form(String s) {
        return java.net.URLEncoder.encode(s, StandardCharsets.UTF_8);
    }

    // ── Microsoft ────────────────────────────────────────────────────────────────

    @Nested
    class Microsoft {

        String link;
        String subscription;
        String clientState;

        void connect() throws Exception {
            var consent = startConnect("outlook");
            assertThat(consent.get("_url")).startsWith(WM.baseUrl() + "/ms-login/common/oauth2/v2.0/authorize?");
            assertThat(consent.get("scope"))
                    .isEqualTo("openid profile offline_access https://graph.microsoft.com/Calendars.ReadWrite");
            assertThat(consent).containsEntry("code_challenge_method", "S256").containsEntry("response_mode", "query");

            var code = "M.fake-code-" + run;
            WM.stubFor(post(urlPathEqualTo("/ms-login/common/oauth2/v2.0/token"))
                    .withFormParam("grant_type", equalTo("authorization_code"))
                    .withFormParam("code", equalTo(code))
                    .willReturn(okJson("""
                            {"token_type":"Bearer","expires_in":3600,"access_token":"eyJ.fake-graph-access-%1$s",
                             "refresh_token":"M.fake-refresh-1-%1$s",
                             "scope":"https://graph.microsoft.com/Calendars.ReadWrite openid profile",
                             "id_token":"%2$s"}""".formatted(
                                    run,
                                    idToken("{\"oid\":\"ms-oid-" + run
                                            + "\",\"preferred_username\":\"jas@prairiewrench.onmicrosoft.com\"}")))));
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/me/calendar"))
                    .willReturn(okJson("{\"id\":\"AAMk-default-" + run + "\",\"name\":\"Calendar\"}")));
            var day = LocalDate.now(ZONE).plusDays(1);
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/me/calendars/AAMk-default-" + run + "/calendarView/delta"))
                    .withQueryParam("startDateTime", WireMock.matching(".+Z"))
                    .withHeader("Prefer", containing("outlook.timezone=\"UTC\""))
                    .willReturn(okJson("""
                            {"value":[
                              {"id":"m-busy-%1$s","showAs":"busy","isCancelled":false,"subject":"Dentist",
                               "start":{"dateTime":"%2$sT16:00:00.0000000","timeZone":"UTC"},
                               "end":{"dateTime":"%2$sT17:00:00.0000000","timeZone":"UTC"}},
                              {"id":"m-free-%1$s","showAs":"free",
                               "start":{"dateTime":"%2$sT18:00:00.0000000","timeZone":"UTC"},
                               "end":{"dateTime":"%2$sT19:00:00.0000000","timeZone":"UTC"}}
                            ],"@odata.nextLink":"%3$s/graph/v1.0/delta-page-2-%1$s"}""".formatted(run, day, WM.baseUrl()))));
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/delta-page-2-" + run))
                    .willReturn(okJson("""
                            {"value":[
                              {"id":"m-oof-%1$s","showAs":"oof",
                               "start":{"dateTime":"%2$sT20:00:00.0000000","timeZone":"UTC"},
                               "end":{"dateTime":"%2$sT21:00:00.0000000","timeZone":"UTC"}}
                            ],"@odata.deltaLink":"%3$s/graph/v1.0/delta-%1$s?$deltatoken=1"}""".formatted(run, day, WM.baseUrl()))));
            WM.stubFor(post(urlPathEqualTo("/graph/v1.0/subscriptions"))
                    .withRequestBody(
                            matchingJsonPath("$.resource", equalTo("me/calendars/AAMk-default-" + run + "/events")))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("""
                            {"id":"sub-%s","expirationDateTime":"%s"}""".formatted(run, Instant.now().plus(Duration.ofDays(5))))));

            callback(
                    "outlook",
                    code,
                    consent.get("state"),
                    "/b/" + biz.merchantId() + "/availability?calendar=outlook&result=connected");
            link = linkId("outlook");
            awaitQuiet(link);
            subscription = "sub-" + run;
            var request = WM.findAll(postRequestedFor(urlPathEqualTo("/graph/v1.0/subscriptions"))
                            .withRequestBody(containing("AAMk-default-" + run)))
                    .getFirst()
                    .getBodyAsString();
            clientState = JsonPath.read(request, "$.clientState");
            assertThat((String) JsonPath.read(request, "$.notificationUrl"))
                    .isEqualTo("https://api.test.northline.invalid/api/v1/webhooks/calendar/microsoft");
            assertThat((String) JsonPath.read(request, "$.lifecycleNotificationUrl"))
                    .isEqualTo("https://api.test.northline.invalid/api/v1/webhooks/calendar/microsoft/lifecycle");
        }

        private org.springframework.test.web.servlet.RequestBuilder notification(
                String state, String event, String etag) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                            "/api/v1/webhooks/calendar/microsoft")
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""
                            {"value":[{"subscriptionId":"%s","clientState":"%s","changeType":"deleted",
                              "resource":"Users/x/Events/%s","tenantId":"t",
                              "resourceData":{"@odata.type":"#Microsoft.Graph.Event","id":"%s","@odata.etag":"%s"}}]}""".formatted(subscription, state, event, event, etag));
        }

        @Test
        void connect_delta_notifications_rotation_andWriteBack() throws Exception {
            connect();
            assertThat(busyIds(link)).containsExactlyInAnyOrder("m-busy-" + run, "m-oof-" + run);
            assertThat(jdbc.sql("select external_id from availability.calendar_channels where link_id = ?")
                            .params(link)
                            .query(String.class)
                            .single())
                    .isEqualTo(subscription);

            // a change notification: verified by clientState, read through the delta link
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/delta-" + run))
                    .withQueryParam("$deltatoken", equalTo("1"))
                    .withHeader("Authorization", equalTo("Bearer eyJ.fake-graph-access-" + run))
                    .willReturn(okJson("""
                            {"value":[{"id":"m-busy-%1$s","@removed":{"reason":"deleted"}}],
                             "@odata.deltaLink":"%2$s/graph/v1.0/delta-%1$s?$deltatoken=2"}""".formatted(run, WM.baseUrl()))));
            mvc.perform(notification("wrong-" + clientState, "m-busy-" + run, "etag-1"))
                    .andExpect(status().isForbidden());
            mvc.perform(notification(clientState, "m-busy-" + run, "etag-1")).andExpect(status().isAccepted());
            mvc.perform(notification(clientState, "m-busy-" + run, "etag-1")).andExpect(status().isAccepted());
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() -> assertThat(busyIds(link)).containsExactly("m-oof-" + run));
            assertThat(jdbc.sql("select count(*) from availability.calendar_notifications where channel_id in"
                                    + " (select id from availability.calendar_channels where link_id = ?)")
                            .params(link)
                            .query(Integer.class)
                            .single())
                    .as("the duplicate delivery was recorded once")
                    .isEqualTo(1);

            // 401 with the cached token: refresh once (Microsoft rotates the refresh token), then retry
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/delta-" + run))
                    .withQueryParam("$deltatoken", equalTo("2"))
                    .withHeader("Authorization", equalTo("Bearer eyJ.fake-graph-access-" + run))
                    .willReturn(aResponse()
                            .withStatus(401)
                            .withBody("{\"error\":{\"code\":\"InvalidAuthenticationToken\"}}")));
            WM.stubFor(get(urlPathEqualTo("/graph/v1.0/delta-" + run))
                    .withQueryParam("$deltatoken", equalTo("2"))
                    .withHeader("Authorization", equalTo("Bearer eyJ.fake-graph-access-2-" + run))
                    .willReturn(okJson("""
                            {"value":[],"@odata.deltaLink":"%2$s/graph/v1.0/delta-%1$s?$deltatoken=3"}""".formatted(run, WM.baseUrl()))));
            WM.stubFor(post(urlPathEqualTo("/ms-login/common/oauth2/v2.0/token"))
                    .withFormParam("grant_type", equalTo("refresh_token"))
                    .withFormParam("refresh_token", equalTo("M.fake-refresh-1-" + run))
                    .willReturn(okJson("""
                            {"token_type":"Bearer","expires_in":3600,"access_token":"eyJ.fake-graph-access-2-%1$s",
                             "refresh_token":"M.fake-refresh-2-%1$s","scope":"https://graph.microsoft.com/Calendars.ReadWrite"}""".formatted(run))));
            assertThat(sync.read(link, "AAMk-default-" + run)).isTrue();
            assertThat(sealer.open(sealedToken(link), link)).isEqualTo("M.fake-refresh-2-" + run);
            assertThat(state(link)).isEqualTo("connected");

            // lifecycle: reauthorizationRequired extends the subscription
            WM.stubFor(WireMock.patch(urlPathEqualTo("/graph/v1.0/subscriptions/" + subscription))
                    .willReturn(okJson("{\"id\":\"%s\",\"expirationDateTime\":\"%s\"}"
                            .formatted(subscription, Instant.now().plus(Duration.ofDays(6))))));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/webhooks/calendar/microsoft/lifecycle")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("""
                                    {"value":[{"subscriptionId":"%s","clientState":"%s","lifecycleEvent":"reauthorizationRequired",
                                      "subscriptionExpirationDateTime":"%s"}]}""".formatted(
                                    subscription, clientState, Instant.now().plus(Duration.ofHours(1)))))
                    .andExpect(status().isAccepted());
            await().atMost(Duration.ofSeconds(15))
                    .untilAsserted(() ->
                            WM.verify(patchRequestedFor(urlPathEqualTo("/graph/v1.0/subscriptions/" + subscription))
                                    .withRequestBody(matchingJsonPath("$.expirationDateTime"))));

            // write-back through Graph: transactionId per booking, text without the last name
            var customer = data.user("Amara Osei");
            var job = new OperationsFixtures(jdbc)
                    .job(biz.merchantId(), tech, customer, Instant.now().plus(Duration.ofDays(4)), "confirmed");
            WM.stubFor(post(urlPathEqualTo("/graph/v1.0/me/calendars/AAMk-default-" + run + "/events"))
                    .willReturn(aResponse()
                            .withStatus(201)
                            .withHeader("Content-Type", "application/json")
                            .withBody("{\"id\":\"graph-evt-" + run + "\"}")));
            assertThat(sync.writeBack(link)).isEqualTo(1);
            var created = WM.findAll(postRequestedFor(
                            urlPathEqualTo("/graph/v1.0/me/calendars/AAMk-default-" + run + "/events")))
                    .getFirst()
                    .getBodyAsString();
            assertThat((String) JsonPath.read(created, "$.subject")).isEqualTo("Brake inspection · Amara");
            assertThat((String) JsonPath.read(created, "$.transactionId")).isEqualTo("nl-" + job);
            assertThat((String) JsonPath.read(created, "$.start.timeZone")).isEqualTo("UTC");
            assertThat(created).doesNotContain("Osei", "P2 stall");

            // the member deleted the event in Outlook: a reschedule creates it again
            WM.stubFor(WireMock.patch(urlPathEqualTo("/graph/v1.0/me/events/graph-evt-" + run))
                    .willReturn(aResponse().withStatus(404).withBody("{\"error\":{\"code\":\"ErrorItemNotFound\"}}")));
            jdbc.sql("update booking.bookings set starts_at = starts_at + interval '30 minutes' where id = ?")
                    .params(job)
                    .update();
            assertThat(sync.writeBack(link)).isEqualTo(1);
            WM.verify(2, postRequestedFor(urlPathEqualTo("/graph/v1.0/me/calendars/AAMk-default-" + run + "/events")));

            // disconnect: subscription and upcoming event deleted
            WM.stubFor(WireMock.delete(urlPathMatching("/graph/v1.0/(subscriptions|me/events)/.*"))
                    .willReturn(aResponse().withStatus(204)));
            mvc.perform(delete(path("/calendars/outlook")).with(TestJwt.member(tech)))
                    .andExpect(status().isOk());
            WM.verify(deleteRequestedFor(urlPathEqualTo("/graph/v1.0/subscriptions/" + subscription)));
            WM.verify(deleteRequestedFor(urlPathEqualTo("/graph/v1.0/me/events/graph-evt-" + run)));
        }

        @Test
        void validationHandshake_isEchoedOnlyWhileASubscriptionIsBeingCreated() throws Exception {
            connect();
            var pending = Ids.next().toLowerCase(Locale.ROOT);
            jdbc.sql("""
                            insert into availability.calendar_channels (id, link_id, calendar_id, provider, external_id,
                                   secret_hash, expires_at, created_at)
                            values (?, ?, ?, 'outlook', null, 'x', now() + interval '1 day', now())
                            """).params(pending, link, "AAMk-default-" + run).update();
            try {
                mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                        "/api/v1/webhooks/calendar/microsoft")
                                .param("validationToken", "Validation: Testing client application reachability"))
                        .andExpect(status().isOk())
                        .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("text/plain")))
                        .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                        .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                                .string("Validation: Testing client application reachability"));
            } finally {
                jdbc.sql("delete from availability.calendar_channels where id = ?")
                        .params(pending)
                        .update();
            }
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                    "/api/v1/webhooks/calendar/microsoft")
                            .param("validationToken", "again"))
                    .andExpect(status().isForbidden());
        }
    }
}
