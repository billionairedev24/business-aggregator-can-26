package ca.northline.bff;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.containing;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.github.tomakehurst.wiremock.WireMockServer;
import java.time.Instant;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.oauth2.core.OAuth2AccessToken.TokenType;
import org.springframework.security.oauth2.core.OAuth2RefreshToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * S-19: a BFF session ends once northline-auth says its token is inactive (the sign-in was revoked in Settings ›
 * Security), checked by introspection — here on every request ({@code session-check-interval=0s}) against a WireMock
 * stand-in of {@code /oauth2/introspect}.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BffSessionRevocationTest {

    static final WireMockServer AUTH = new WireMockServer(wireMockConfig().dynamicPort());

    static {
        AUTH.start();
    }

    @DynamicPropertySource
    static void auth(DynamicPropertyRegistry registry) {
        registry.add("northline.bff.introspection-uri", () -> AUTH.baseUrl() + "/oauth2/introspect");
        registry.add("northline.bff.session-check-interval", () -> "0s");
    }

    @AfterAll
    static void stop() {
        AUTH.stop();
    }

    @Autowired
    MockMvc mvc;

    @Autowired
    ClientRegistrationRepository registrations;

    @Autowired
    OAuth2AuthorizedClientRepository authorizedClients;

    @BeforeEach
    void reset() {
        AUTH.resetAll();
    }

    private void introspectionSays(String body, int status) {
        AUTH.stubFor(post(urlEqualTo("/oauth2/introspect"))
                .willReturn(aResponse()
                        .withStatus(status)
                        .withHeader("Content-Type", "application/json")
                        .withBody(body)));
    }

    /** A signed-in BFF session: the OIDC login plus the tokens kept in the HTTP session. */
    private MockHttpServletRequestBuilder session(MockHttpSession session) {
        var registration = registrations.findByRegistrationId("studio");
        var now = Instant.now();
        var client = new OAuth2AuthorizedClient(
                registration,
                "01J9ZD3V00000000000000RAV1",
                new OAuth2AccessToken(TokenType.BEARER, "access", now, now.plusSeconds(600)),
                new OAuth2RefreshToken("refresh-token-value", now));
        var request = new MockHttpServletRequest();
        request.setSession(session);
        authorizedClients.saveAuthorizedClient(
                client,
                new TestingAuthenticationToken("01J9ZD3V00000000000000RAV1", null),
                request,
                new MockHttpServletResponse());
        return get("/bff/session")
                .session(session)
                .with(oidcLogin()
                        .clientRegistration(registration)
                        .idToken(t -> t.subject("01J9ZD3V00000000000000RAV1")
                                .claim("given_name", "Ravi")
                                .claim("sid", "01J9ZD3V0000000000000SESS1")));
    }

    @Test
    void anActiveToken_keepsTheSession_andTheSidIsShown() throws Exception {
        introspectionSays("{\"active\":true}", 200);
        var session = new MockHttpSession();
        mvc.perform(session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sid").value("01J9ZD3V0000000000000SESS1"));
        assertThat(session.isInvalid()).isFalse();
        AUTH.verify(postRequestedFor(urlEqualTo("/oauth2/introspect"))
                .withBasicAuth(
                        new com.github.tomakehurst.wiremock.client.BasicCredentials("studio-bff", "dev-studio-bff"))
                .withRequestBody(containing("token=refresh-token-value"))
                .withRequestBody(containing("token_type_hint=refresh_token")));
    }

    @Test
    void anInactiveToken_endsTheSession() throws Exception {
        introspectionSays("{\"active\":false}", 200);
        var session = new MockHttpSession();
        mvc.perform(session(session)).andExpect(status().isUnauthorized());
        assertThat(session.isInvalid()).isTrue();
    }

    @Test
    void anUnreachableAuthServer_failsOpen() throws Exception {
        introspectionSays("{}", 503);
        var session = new MockHttpSession();
        mvc.perform(session(session)).andExpect(status().isOk());
        assertThat(session.isInvalid()).isFalse();
    }
}
