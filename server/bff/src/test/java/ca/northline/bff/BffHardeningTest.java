package ca.northline.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.get;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.cookie;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
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
 * S-20 security review of the BFF hand-off, with the cloud's cookie names ({@code __Host-NL_STUDIO},
 * {@code __Host-XSRF-TOKEN}) and a WireMock stand-in of northline-auth's token and JWK set endpoints: the full
 * authorization code + PKCE callback gives the session a new id (fixation) and lands on {@code next}; the CSRF token is
 * taken from the header only; the CSRF cookie is Secure/Strict/path=/; every answer forbids framing.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@TestPropertySource(
        properties = {
            "northline.bff.csrf-cookie-name=__Host-XSRF-TOKEN",
            "server.servlet.session.cookie.name=__Host-NL_STUDIO",
        })
class BffHardeningTest {

    static final WireMockServer AUTH = new WireMockServer(wireMockConfig().dynamicPort());
    static final ECKey KEY;

    static {
        AUTH.start();
        try {
            KEY = new ECKeyGenerator(Curve.P_256).keyID("s20").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        AUTH.stubFor(get(urlEqualTo("/oauth2/jwks"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(new JWKSet(KEY.toPublicJWK()).toString())));
    }

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add(
                "spring.security.oauth2.client.provider.northline.token-uri", () -> AUTH.baseUrl() + "/oauth2/token");
        registry.add(
                "spring.security.oauth2.client.provider.northline.jwk-set-uri", () -> AUTH.baseUrl() + "/oauth2/jwks");
        registry.add("northline.bff.introspection-uri", () -> AUTH.baseUrl() + "/oauth2/introspect");
    }

    @AfterAll
    static void stop() {
        AUTH.stop();
    }

    @Autowired
    MockMvc mvc;

    private static String idToken(String nonce) throws Exception {
        var now = Instant.now();
        var jwt = new SignedJWT(
                new JWSHeader.Builder(JWSAlgorithm.ES256).keyID(KEY.getKeyID()).build(),
                new JWTClaimsSet.Builder()
                        .issuer("http://localhost:9000")
                        .subject("01J9ZD3V00000000000000RAV1")
                        .audience("studio-bff")
                        .issueTime(Date.from(now))
                        .expirationTime(Date.from(now.plusSeconds(1800)))
                        .claim("nonce", nonce)
                        .claim("given_name", "Ravi")
                        .claim("family_name", "Sandhu")
                        .claim("acr", "mfa")
                        .claim("sid", "01J9ZD3V0000000000000SESS1")
                        .build());
        jwt.sign(new ECDSASigner(KEY));
        return jwt.serialize();
    }

    @Test
    void signingIn_givesTheSessionANewId_andLandsOnNext() throws Exception {
        var session = new MockHttpSession();
        mvc.perform(MockMvcRequestBuilders.get("/bff/login")
                        .param("next", "/b/01J9ZD3V00000000000000PWM1/orders")
                        .session(session))
                .andExpect(status().isFound());
        var authorize = mvc.perform(MockMvcRequestBuilders.get("/oauth2/authorization/studio")
                        .session(session))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var params = UriComponentsBuilder.fromUriString(authorize).build().getQueryParams();
        assertThat(params.getFirst("code_challenge_method")).isEqualTo("S256");
        var state = URLDecoder.decode(params.getFirst("state"), StandardCharsets.UTF_8);
        var nonce = URLDecoder.decode(params.getFirst("nonce"), StandardCharsets.UTF_8);
        AUTH.stubFor(post(urlEqualTo("/oauth2/token"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"access_token":"at-s20","token_type":"Bearer","expires_in":600,\
                                "refresh_token":"rt-s20","scope":"openid profile merchant","id_token":"%s"}""".formatted(idToken(nonce)))));

        var before = session.getId();
        mvc.perform(MockMvcRequestBuilders.get("/login/oauth2/code/studio")
                        .param("code", "code-s20")
                        .param("state", state)
                        .session(session))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/b/01J9ZD3V00000000000000PWM1/orders"));

        assertThat(session.getId()).isNotEqualTo(before);
        AUTH.verify(postRequestedFor(urlEqualTo("/oauth2/token")).withRequestBody(containing("code_verifier=")));
        mvc.perform(MockMvcRequestBuilders.get("/bff/session").session(session)).andExpect(status().isOk());
    }

    @Test
    void theCallback_withAnotherBrowsersState_signsNobodyIn() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/login/oauth2/code/studio")
                        .param("code", "code-s20")
                        .param("state", "someone-elses-state")
                        .session(new MockHttpSession()))
                .andExpect(status().isFound())
                .andExpect(redirectedUrl("/sign-in?error=signin"));
    }

    @Test
    void theCsrfCookie_isHostPrefixed_secure_strict_andReadableByTheStudio() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/bff/session"))
                .andExpect(cookie().exists("__Host-XSRF-TOKEN"))
                .andExpect(cookie().doesNotExist("XSRF-TOKEN"))
                .andExpect(cookie().secure("__Host-XSRF-TOKEN", true))
                .andExpect(cookie().path("__Host-XSRF-TOKEN", "/"))
                .andExpect(cookie().sameSite("__Host-XSRF-TOKEN", "Strict"))
                .andExpect(cookie().httpOnly("__Host-XSRF-TOKEN", false));
    }

    @Test
    void theCsrfToken_countsOnlyInTheHeader_notAsAFormField() throws Exception {
        var session = new MockHttpSession();
        var xsrf = mvc.perform(MockMvcRequestBuilders.get("/bff/session").session(session))
                .andReturn()
                .getResponse()
                .getCookie("__Host-XSRF-TOKEN");
        assertThat(xsrf).isNotNull();
        // A form on a sibling subdomain (same site) that planted or read the cookie still can't post it as _csrf.
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout")
                        .session(session)
                        .with(oidcLogin())
                        .cookie(xsrf)
                        .param("_csrf", xorMasked(xsrf.getValue())))
                .andExpect(status().isForbidden());
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout")
                        .session(session)
                        .with(oidcLogin())
                        .cookie(xsrf)
                        .header("X-XSRF-TOKEN", xsrf.getValue()))
                .andExpect(status().isNoContent())
                .andExpect(cookie().maxAge("__Host-NL_STUDIO", 0));
        assertThat(session.isInvalid()).isTrue();
    }

    /**
     * The form-field encoding Spring's SPA handler used to accept ({@code XorCsrfTokenRequestAttributeHandler}): anyone
     * who knows the raw token (e.g. because they planted the cookie) can compute it.
     */
    private static String xorMasked(String token) {
        var raw = token.getBytes(StandardCharsets.UTF_8);
        var random = new byte[raw.length];
        new java.security.SecureRandom().nextBytes(random);
        var combined = new byte[raw.length * 2];
        System.arraycopy(random, 0, combined, 0, raw.length);
        for (int i = 0; i < raw.length; i++) {
            combined[raw.length + i] = (byte) (random[i] ^ raw[i]);
        }
        return java.util.Base64.getUrlEncoder().encodeToString(combined);
    }

    @Test
    void aForeignCookieValue_isNoToken() throws Exception {
        var planted = new Cookie("XSRF-TOKEN", "planted-by-a-sibling");
        mvc.perform(MockMvcRequestBuilders.post("/bff/logout")
                        .with(oidcLogin())
                        .cookie(planted)
                        .header("X-XSRF-TOKEN", planted.getValue()))
                .andExpect(status().isForbidden());
    }

    @Test
    void everyAnswer_forbidsFraming() throws Exception {
        mvc.perform(MockMvcRequestBuilders.get("/bff/session"))
                .andExpect(header().string("X-Frame-Options", "DENY"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(header().string(
                                "Content-Security-Policy",
                                "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"));
    }
}
