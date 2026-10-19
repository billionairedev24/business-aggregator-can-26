package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.DpopKey;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Redesign phase 4 (docs/runbooks/mobile-local.md): with {@code northline.auth.local-network-devices} on (the
 * {@code local} profile), the consumer app on a phone that reaches this machine as {@code 192.168.1.20} signs in with
 * the network variant of its registered loopback redirect, and an unauthenticated request is sent to the sign-in page
 * on that host, whose browser may call the JSON API from the network twin of an allowed origin. Other hosts, ports and
 * paths stay refused.
 */
@TestPropertySource(properties = "northline.auth.local-network-devices=true")
class LocalNetworkDevicesApiTest extends AuthIntegrationTest {

    private static final String LAN = "http://192.168.1.20:9000";
    private static final String APP = "mobile-consumer";
    private static final String LAN_REDIRECT = "http://192.168.1.20:3000/app/oauth2redirect";
    private static final String VERIFIER = "phase4-verifier-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGH";

    private MockHttpServletRequestBuilder authorize(String base, MockHttpSession session, String redirectUri)
            throws Exception {
        return get(base + "/oauth2/authorize")
                .session(session)
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", APP)
                .queryParam("scope", "openid profile orders bookings offline_access")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", "phase4")
                .queryParam("code_challenge", challenge())
                .queryParam("code_challenge_method", "S256");
    }

    private MockHttpSession signedIn() throws Exception {
        var user = register(newPerson());
        var session = new MockHttpSession();
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        mvc.perform(post(LAN + "/api/auth/sign-in")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("identifier", user.person().email()))))
                .andExpect(status().isOk());
        mvc.perform(post(LAN + "/api/auth/sign-in/totp")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(user.totpSecret())))))
                .andExpect(status().isOk());
        return session;
    }

    @Test
    void aPhoneOnTheLan_isSentToTheSignInPageOnTheHostItCameIn() throws Exception {
        var location = mvc.perform(authorize(LAN, new MockHttpSession(), LAN_REDIRECT))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith("http://192.168.1.20:3000/sign-in");
    }

    @Test
    void theSameRequestOnLocalhost_keepsTheConfiguredPage() throws Exception {
        mvc.perform(authorize("http://localhost:9000", new MockHttpSession(), "http://localhost:3000/app/oauth2redirect"))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3000/sign-in"));
    }

    @Test
    void aSignedInPhone_getsItsCodeOnTheNetworkRedirect_andExchangesIt() throws Exception {
        var location = mvc.perform(authorize(LAN, signedIn(), LAN_REDIRECT))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(LAN_REDIRECT + "?code=");
        var code = UriComponentsBuilder.fromUriString(String.valueOf(location))
                .build()
                .getQueryParams()
                .getFirst("code");

        var key = DpopKey.generate();
        var tokenUrl = LAN + "/oauth2/token";
        var nonce = mvc.perform(exchange(tokenUrl, String.valueOf(code), LAN_REDIRECT)
                        .header("DPoP", key.proof("POST", tokenUrl, null)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("use_dpop_nonce"))
                .andReturn()
                .getResponse()
                .getHeader("DPoP-Nonce");
        mvc.perform(exchange(tokenUrl, String.valueOf(code), LAN_REDIRECT)
                        .header("DPoP", key.proof("POST", tokenUrl, nonce)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("DPoP"));
    }

    @Test
    void aPublicHost_orAnotherPath_isNotARegisteredRedirect() throws Exception {
        for (var redirect : new String[] {
            "http://evil.example:3000/app/oauth2redirect",
            "http://8.8.8.8:3000/app/oauth2redirect",
            "http://192.168.1.20:3000/app/elsewhere",
            "http://192.168.1.20:3001/app/oauth2redirect",
            "https://192.168.1.20:3000/app/oauth2redirect"
        }) {
            mvc.perform(authorize(LAN, new MockHttpSession(), redirect)).andExpect(status().isBadRequest());
        }
    }

    @Test
    void theSignInPageOnTheNetwork_mayCallTheJsonApi_otherOriginsMayNot() throws Exception {
        // the test profile allows the Studio's http://localhost:3100; its network twin passes CORS and the Origin check
        mvc.perform(options(LAN + "/api/auth/sign-in")
                        .header("Origin", "http://192.168.1.20:3100")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://192.168.1.20:3100"));
        var body = json(Map.of("identifier", "nobody." + System.nanoTime() + "@example.ca"));
        mvc.perform(post(LAN + "/api/auth/sign-in")
                        .header("Origin", "http://192.168.1.20:3100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(result -> assertThat(result.getResponse().getStatus()).isNotEqualTo(403));
        mvc.perform(post(LAN + "/api/auth/sign-in")
                        .header("Origin", "http://evil.example:3100")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body))
                .andExpect(status().isForbidden());
    }

    private static MockHttpServletRequestBuilder exchange(String tokenUrl, String code, String redirectUri) {
        return post(tokenUrl)
                .param("grant_type", "authorization_code")
                .param("client_id", APP)
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("code_verifier", VERIFIER);
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }
}
