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
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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
import java.util.List;
import org.jspecify.annotations.Nullable;
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
 * S-90 console-bff (the {@code console} profile) against WireMock stand-ins of the api and northline-auth: the sign-in
 * uses the console-bff client; only staff who signed in with a second factor keep a session (anyone else is signed
 * out, their refresh token revoked, and sent to {@code /sign-in?error=…}); the relay carries the access token and the
 * role view header but never the browser's own credentials; CSRF is header-only. Cloud cookie names.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles({"test", "console"})
@TestPropertySource(
        properties = {
            "northline.bff.csrf-cookie-name=__Host-XSRF-TOKEN",
            "server.servlet.session.cookie.name=__Host-NL_CONSOLE",
        })
class ConsoleBffTest {

    static final WireMockServer AUTH = new WireMockServer(wireMockConfig().dynamicPort());
    // Plain HTTP/1.1 like the api's Tomcat (the relay's JDK client would try an h2c upgrade Jetty cancels).
    static final WireMockServer API =
            new WireMockServer(wireMockConfig().dynamicPort().http2PlainDisabled(true));
    static final ECKey KEY;

    static {
        AUTH.start();
        API.start();
        try {
            KEY = new ECKeyGenerator(Curve.P_256).keyID("s90").generate();
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
        registry.add("northline.bff.revocation-uri", () -> AUTH.baseUrl() + "/oauth2/revoke");
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
    void stub() {
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
        AUTH.stubFor(post(urlEqualTo("/oauth2/revoke")).willReturn(aResponse().withStatus(200)));
        API.stubFor(WireMock.any(anyUrl())
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("{\"ok\":true}")));
    }

    private static String idToken(String nonce, List<String> roles, @Nullable String acr) throws Exception {
        var now = Instant.now();
        var claims = new JWTClaimsSet.Builder()
                .issuer("http://localhost:9000")
                .subject("01J9ZD3V00000000000000PNA1")
                .audience("console-bff")
                .issueTime(Date.from(now))
                .expirationTime(Date.from(now.plusSeconds(1800)))
                .claim("nonce", nonce)
                .claim("given_name", "Priya")
                .claim("family_name", "Natarajan")
                .claim("roles", roles)
                .claim("sid", "01J9ZD3V0000000000000SESS9");
        if (acr != null) {
            claims.claim("acr", acr);
        }
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY.getKeyID()).build(), claims.build());
        jwt.sign(new ECDSASigner(KEY));
        return jwt.serialize();
    }

    /** Runs the sign-in hand-off and the OAuth callback; returns the callback's redirect. */
    private @Nullable String signIn(MockHttpSession session, List<String> roles, @Nullable String acr)
            throws Exception {
        mvc.perform(get("/bff/login").param("next", "/finance").session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/oauth2/authorization/console"));
        var authorize = mvc.perform(get("/oauth2/authorization/console").session(session))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", containsString("client_id=console-bff")))
                .andExpect(header().string("Location", containsString("scope=openid%20profile%20console")))
                .andExpect(header().string("Location", containsString("code_challenge_method=S256")))
                .andExpect(header().string(
                                "Location", containsString("redirect_uri=http://localhost/login/oauth2/code/console")))
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
                                {"access_token":"at-s90","token_type":"Bearer","expires_in":600,\
                                "refresh_token":"rt-s90","scope":"openid profile console","id_token":"%s"}""".formatted(idToken(nonce, roles, acr)))));
        return mvc.perform(get("/login/oauth2/code/console")
                        .param("code", "code-s90")
                        .param("state", state)
                        .session(session))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    @Test
    void signedOut_theSessionIs401_andTheApiIsClosed() throws Exception {
        mvc.perform(get("/bff/session"))
                .andExpect(status().isUnauthorized())
                .andExpect(cookie().exists("__Host-XSRF-TOKEN"));
        mvc.perform(get("/api/v1/console/me")).andExpect(status().isUnauthorized());
        API.verify(0, anyRequestedFor(anyUrl()));
    }

    @Test
    void staffWithASecondFactor_signIn_andTheRelayCarriesTheTokenAndTheRoleView() throws Exception {
        var session = new MockHttpSession();
        assertThat(signIn(session, List.of("staff", "finance"), "mfa")).isEqualTo("/finance");

        mvc.perform(get("/bff/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.firstName").value("Priya"))
                .andExpect(jsonPath("$.user.initials").value("PN"))
                .andExpect(jsonPath("$.acr").value("mfa"));

        mvc.perform(get("/api/v1/console/me")
                        .session(session)
                        .header("X-Console-Role", "finance")
                        .header("Authorization", "Bearer forged")
                        .header("X-Dev-User", "01J9ZD3V00000000000000RAV1")
                        .cookie(new Cookie("__Host-NL_CONSOLE", "session")))
                .andExpect(status().isOk());
        API.verify(getRequestedFor(urlEqualTo("/api/v1/console/me"))
                .withHeader("Authorization", equalTo("Bearer at-s90"))
                .withHeader("X-Console-Role", equalTo("finance"))
                .withHeader("X-Dev-User", absent())
                .withHeader("Cookie", absent()));
    }

    @Test
    void someoneWithoutTheStaffRole_isSignedOutAtOnce() throws Exception {
        var session = new MockHttpSession();
        assertThat(signIn(session, List.of(), "mfa")).isEqualTo("/sign-in?error=staff_only");
        assertThat(session.isInvalid()).isTrue();
        AUTH.verify(
                postRequestedFor(urlEqualTo("/oauth2/revoke")).withRequestBody(WireMock.containing("token=rt-s90")));
        mvc.perform(get("/bff/session").session(new MockHttpSession())).andExpect(status().isUnauthorized());
    }

    @Test
    void staffWithoutASecondFactor_isSignedOutAtOnce() throws Exception {
        var session = new MockHttpSession();
        assertThat(signIn(session, List.of("staff", "admin"), null)).isEqualTo("/sign-in?error=mfa_required");
        assertThat(session.isInvalid()).isTrue();
        AUTH.verify(postRequestedFor(urlEqualTo("/oauth2/revoke")));
    }

    @Test
    void writes_needTheCsrfHeader() throws Exception {
        var session = new MockHttpSession();
        signIn(session, List.of("staff", "trust_safety"), "mfa");
        var xsrf = mvc.perform(get("/bff/session").session(session))
                .andReturn()
                .getResponse()
                .getCookie("__Host-XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        var body = "{\"role\":\"trust_safety\"}";
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/console/me/role-view")
                        .session(session)
                        .cookie(xsrf)
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/console/me/role-view")
                        .session(session)
                        .cookie(xsrf)
                        .param("_csrf", xsrf.getValue())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isForbidden());
        API.verify(0, anyRequestedFor(anyUrl()));
        mvc.perform(MockMvcRequestBuilders.post("/api/v1/console/me/role-view")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue())
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());
        API.verify(postRequestedFor(urlEqualTo("/api/v1/console/me/role-view"))
                .withHeader("Authorization", equalTo("Bearer at-s90")));
    }

    @Test
    void signingOut_clearsTheConsoleCookie() throws Exception {
        var session = new MockHttpSession();
        signIn(session, List.of("staff", "analyst"), "mfa");
        var xsrf = mvc.perform(get("/bff/session").session(session))
                .andReturn()
                .getResponse()
                .getCookie("__Host-XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout")
                        .session(session)
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("__Host-NL_CONSOLE", 0));
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void theOtherAppsClients_areNotRegisteredHere() throws Exception {
        mvc.perform(get("/oauth2/authorization/studio")).andExpect(header().doesNotExist("Location"));
        mvc.perform(get("/oauth2/authorization/northline")).andExpect(header().doesNotExist("Location"));
    }

    @Test
    void aFailedCallback_landsOnTheConsoleSignInPage() throws Exception {
        mvc.perform(get("/login/oauth2/code/console")
                        .param("code", "c")
                        .param("state", "someone-elses-state")
                        .session(new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/sign-in?error=signin"));
    }
}
