package ca.northline.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.absent;
import static com.github.tomakehurst.wiremock.client.WireMock.anyRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.anyUrl;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.getRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.matchesPattern;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.bff.config.Guests;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import jakarta.servlet.http.Cookie;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-45 consumer-bff (the {@code consumer} profile) against WireMock stand-ins of the api and of northline-auth's token
 * and JWK set endpoints: guests get a session with a guest id and browse the api without a token; POSTs still need
 * the CSRF header; the sign-in hand-off uses the consumer-bff client (no second factor needed) and keeps the guest id;
 * signed in, the relay carries the access token. Cloud cookie names, as in {@link BffHardeningTest}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "consumer"})
@TestPropertySource(
        properties = {
            "northline.bff.csrf-cookie-name=__Host-XSRF-TOKEN",
            "server.servlet.session.cookie.name=__Host-NL_CONSUMER",
            "northline.bff.client-city-header=X-Client-City",
        })
class ConsumerBffTest {

    static final WireMockServer AUTH = new WireMockServer(wireMockConfig().dynamicPort());
    // Plain HTTP/1.1 like the api's Tomcat (the relay's JDK client would try an h2c upgrade Jetty cancels).
    static final WireMockServer API =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
    static final ECKey KEY;

    static {
        AUTH.start();
        API.start();
        try {
            KEY = new ECKeyGenerator(Curve.P_256).keyID("s45").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    @DynamicPropertySource
    static void stubs(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.client.provider.northline.token-uri", () -> AUTH.baseUrl() + "/oauth2/token");
        registry.add(
                "spring.security.oauth2.client.provider.northline.jwk-set-uri", () -> AUTH.baseUrl() + "/oauth2/jwks");
        registry.add("northline.bff.introspection-uri", () -> AUTH.baseUrl() + "/oauth2/introspect");
        registry.add("northline.bff.api-uri", API::baseUrl);
    }

    @AfterAll
    static void stop() {
        AUTH.stop();
        API.stop();
    }

    @Autowired
    MockMvc mvc;

    @BeforeEach
    void stubApi() {
        API.resetAll();
        AUTH.resetAll();
        AUTH.stubFor(WireMock.get(urlEqualTo("/oauth2/jwks"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(KEY.toPublicJWK()).toString())));
        AUTH.stubFor(post(urlEqualTo("/oauth2/introspect"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"active\":true}")));
        API.stubFor(WireMock.any(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
    }

    private static String guestOf(MockHttpSession session) {
        return (String) session.getAttribute("nl.bff.guest-id");
    }

    private Cookie xsrf(MockHttpSession session) throws Exception {
        var cookie = mvc.perform(get("/bff/session").session(session))
                .andReturn()
                .getResponse()
                .getCookie("__Host-XSRF-TOKEN");
        assertThat(cookie).isNotNull();
        return cookie;
    }

    // ── guests
    // ────────────────────────────────────────────────────────────────────────────────────────────────────────

    @Test
    void aGuest_getsASessionWithAGuestId_andTheCsrfCookie() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/bff/session").session(session).header("X-Client-City", "Red%20Deer"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user").isEmpty())
                .andExpect(jsonPath("$.acr").doesNotExist())
                .andExpect(jsonPath("$.guestId").value(matchesPattern("g_[A-Za-z0-9_-]{22}")))
                .andExpect(jsonPath("$.location.city").value("Red Deer"))
                .andExpect(cookie().exists("__Host-XSRF-TOKEN"))
                .andExpect(cookie().secure("__Host-XSRF-TOKEN", true))
                .andExpect(cookie().sameSite("__Host-XSRF-TOKEN", "Strict"));
        var guest = guestOf(session);
        mvc.perform(get("/bff/session").session(session))
                .andExpect(jsonPath("$.guestId").value(guest))
                .andExpect(jsonPath("$.location").doesNotExist());
    }

    @Test
    void aGuest_browsesTheApi_withoutAToken_andWithTheGuestId() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/bff/session").session(session));
        mvc.perform(get("/api/v1/search")
                        .param("q", "sourdough")
                        .session(session)
                        .header("Authorization", "Bearer forged")
                        .header(Guests.HEADER, "g_someone-elses-cart")
                        .header("X-Dev-User", "01J9ZD3V00000000000000RAV1")
                        .cookie(new Cookie("__Host-NL_CONSUMER", "session")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
        API.verify(getRequestedFor(urlPathEqualTo("/api/v1/search"))
                .withQueryParam("q", equalTo("sourdough"))
                .withHeader("Authorization", absent())
                .withHeader("Cookie", absent())
                .withHeader("X-Dev-User", absent())
                .withHeader(Guests.HEADER, equalTo(guestOf(session))));
    }

    @Test
    void aRequestWithoutSession_isRelayedAnonymously_andCreatesNoSession() throws Exception {
        var result = mvc.perform(get("/api/v1/providers/prairie-wrench"))
                .andExpect(status().isOk())
                .andReturn();
        assertThat(result.getRequest().getSession(false)).isNull();
        API.verify(getRequestedFor(urlPathEqualTo("/api/v1/providers/prairie-wrench"))
                .withHeader(Guests.HEADER, absent())
                .withHeader("Authorization", absent()));
    }

    @Test
    void aGuestsPost_needsTheCsrfHeader() throws Exception {
        var session = new MockHttpSession();
        var xsrf = xsrf(session);
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cart/items")
                        .session(session)
                        .cookie(xsrf)
                        .contentType("application/json")
                        .content("{\"listingId\":\"x\"}"))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cart/items")
                        .session(session)
                        .cookie(xsrf)
                        .param("_csrf", xsrf.getValue())
                        .contentType("application/json")
                        .content("{\"listingId\":\"x\"}"))
                .andExpect(status().isForbidden());
        API.verify(0, anyRequestedFor(anyUrl()));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/cart/items")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType("application/json")
                        .content("{\"listingId\":\"x\"}"))
                .andExpect(status().isOk());
        API.verify(postRequestedFor(urlEqualTo("/api/v1/cart/items"))
                .withHeader(Guests.HEADER, equalTo(guestOf(session))));
    }

    // ── sign-in hand-off
    // ──────────────────────────────────────────────────────────────────────────────────────────────

    private static String idToken(String nonce) throws Exception {
        var now = Instant.now();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:9000")
                        .subject("01J9ZD3V00000000000000AMA1")
                        .audience("consumer-bff")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(1800)))
                        .claim("nonce", nonce)
                        .claim("given_name", "Amara")
                        .claim("family_name", "Osei")
                        .claim("email", "amara@example.ca")
                        .claim("sid", "01J9ZD3V0000000000000SESS2")
                        .build()); // no acr: signed in with a phone code only
        jwt.sign(new ECDSASigner(KEY));
        return jwt.serialize();
    }

    @Test
    void signingIn_usesTheConsumerClient_keepsTheGuestId_andRelaysTheToken() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(get("/bff/session").session(session))
                .andExpect(jsonPath("$.user").isEmpty());
        var guest = guestOf(session);

        mvc.perform(get("/bff/login").param("next", "/cart").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/northline"));
        var authorize = mvc.perform(get("/oauth2/authorization/northline").session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("client_id=consumer-bff")))
                .andExpect(header().string("Location", containsString("scope=openid%20profile%20orders%20bookings")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string(
                                "Location",
                                containsString("redirect_uri=http://localhost/login/oauth2/code/northline")))
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var params = UriComponentsBuilder.fromUriString(authorize).build().getQueryParams();
        var state = URLDecoder.decode(params.getFirst("state"), StandardCharsets.UTF_8);
        var nonce = URLDecoder.decode(params.getFirst("nonce"), StandardCharsets.UTF_8);
        AUTH.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"at-s45","token_type":"Bearer","expires_in":600,\
                                "refresh_token":"rt-s45","scope":"openid profile orders bookings","id_token":"%s"}""".formatted(idToken(nonce)))));

        var before = session.getId();
        mvc.perform(get("/login/oauth2/code/northline")
                        .param("code", "code-s45")
                        .param("state", state)
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/cart"));
        assertThat(session.getId()).isNotEqualTo(before);
        AUTH.verify(postRequestedFor(urlEqualTo("/oauth2/token"))
                .withHeader("Authorization", WireMock.containing("Basic "))
                .withRequestBody(WireMock.containing("code_verifier=")));

        mvc.perform(get("/bff/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.firstName").value("Amara"))
                .andExpect(jsonPath("$.user.initials").value("AO"))
                .andExpect(jsonPath("$.acr").doesNotExist())
                .andExpect(jsonPath("$.guestId").value(guest));

        mvc.perform(get("/api/v1/me").session(session)).andExpect(status().isOk());
        API.verify(getRequestedFor(urlEqualTo("/api/v1/me"))
                .withHeader("Authorization", equalTo("Bearer at-s45"))
                .withHeader(Guests.HEADER, equalTo(guest)));
    }

    @Test
    void aFailedCallback_landsOnTheConsumerSignInPage() throws Exception {
        mvc.perform(get("/login/oauth2/code/northline")
                        .param("code", "c")
                        .param("state", "someone-elses-state")
                        .session(new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/sign-in?error=signin"));
    }

    @Test
    void theStudioClient_isNotRegisteredHere() throws Exception {
        // No authorization request is started for the Studio's client (its registration doesn't exist here).
        mvc.perform(get("/oauth2/authorization/studio")).andExpect(header().doesNotExist("Location"));
    }

    @Test
    void signingOut_needsTheCsrfHeader_andClearsTheConsumerCookie() throws Exception {
        var session = new MockHttpSession();
        var xsrf = xsrf(session);
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout").session(session).cookie(xsrf))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("__Host-NL_CONSUMER", 0));
        assertThat(session.isInvalid()).isTrue();
    }
}
