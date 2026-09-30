package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-20 security review of the JSON sign-in flow and the hand-off to the BFF (docs/security/s-20-auth-review.md): one
 * regression test per finding that is fixed here, and the checks that were already right, so they stay right.
 */
class SecurityReviewApiTest extends AuthIntegrationTest {

    private static final String STUDIO = "http://localhost:3100";
    private static final String REDIRECT = STUDIO + "/login/oauth2/code/studio";
    private static final String VERIFIER = "s20-verifier-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFG";

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpSession signIn(Registered user) throws Exception {
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        var session = new MockHttpSession();
        postJson(
                        "/api/auth/sign-in",
                        session,
                        json(Map.of("identifier", user.person().email())))
                .andExpect(status().isOk());
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isOk());
        return session;
    }

    private static String challenge(String verifier) throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII)));
    }

    private MockHttpServletRequestBuilder authorize(MockHttpSession session, String redirectUri) throws Exception {
        return get("/oauth2/authorize")
                .session(session)
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", "studio-bff")
                .queryParam("scope", "openid profile merchant")
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", "s20")
                .queryParam("code_challenge", challenge(VERIFIER))
                .queryParam("code_challenge_method", "S256");
    }

    /** The studio-bff's code exchange from this auth session. */
    private String tokenResponse(MockHttpSession session) throws Exception {
        var location = mvc.perform(authorize(session, REDIRECT))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        return mvc.perform(post("/oauth2/token")
                        .with(httpBasic("studio-bff", "dev-studio-bff"))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/token")
                .with(httpBasic("studio-bff", "dev-studio-bff"))
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken));
    }

    private static Map<String, Object> claims(String jwt) {
        return JsonPath.read(
                new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8), "$");
    }

    @Nested
    class SessionFixation {

        @Test
        void signingIn_givesTheSessionANewId() throws Exception {
            var user = register(newPerson());
            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            var session = new MockHttpSession();
            postJson(
                    "/api/auth/sign-in",
                    session,
                    json(Map.of("identifier", user.person().email())));
            var before = session.getId();
            postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
            assertThat(session.getId()).isNotEqualTo(before);
        }

        @Test
        void creatingTheAccount_givesTheSessionANewId() throws Exception {
            var session = new MockHttpSession();
            var before = session.getId();
            register(newPerson(), session);
            assertThat(session.getId()).isNotEqualTo(before);
        }

        @Test
        void confirmingItsYou_givesTheSessionANewId_andKeepsItSignedIn() throws Exception {
            var user = register(newPerson());
            var before = user.session().getId();
            postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
            assertThat(user.session().getId()).isNotEqualTo(before);
            mvc.perform(get("/api/auth/session").session(user.session())).andExpect(status().isOk());
        }
    }

    @Nested
    class CrossSiteRequests {

        @Test
        void aStateChangingCallFromAnotherOrigin_isRefused() throws Exception {
            // CORS refuses a disallowed origin first; the Origin check is the second line.
            mvc.perform(post("/api/auth/sign-out").header("Origin", "https://evil.example"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            mvc.perform(post("/api/auth/sign-out").header("Origin", "null")).andExpect(status().isForbidden());
        }

        @Test
        void aBrowserRequestMarkedCrossSite_withoutOrigin_isRefused() throws Exception {
            mvc.perform(post("/api/auth/sign-out").header("Sec-Fetch-Site", "cross-site"))
                    .andExpect(status().isForbidden());
        }

        @Test
        void theStudio_andNonBrowserClients_getThrough() throws Exception {
            mvc.perform(post("/api/auth/sign-out").header("Origin", STUDIO).header("Sec-Fetch-Site", "same-site"))
                    .andExpect(status().isNoContent());
            mvc.perform(post("/api/auth/sign-out")).andExpect(status().isNoContent());
        }

        @Test
        void cors_answersOnlyTheAllowedOrigins_withCredentials() throws Exception {
            mvc.perform(options("/api/auth/sign-in")
                            .header("Origin", STUDIO)
                            .header("Access-Control-Request-Method", "POST")
                            .header("Access-Control-Request-Headers", "content-type"))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Access-Control-Allow-Origin", STUDIO))
                    .andExpect(header().string("Access-Control-Allow-Credentials", "true"));
            mvc.perform(options("/api/auth/sign-in")
                            .header("Origin", "https://evil.example")
                            .header("Access-Control-Request-Method", "POST"))
                    .andExpect(status().isForbidden())
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        }
    }

    @Nested
    class Headers {

        @Test
        void theJsonApi_cantBeFramed_andAllowsNothingElse() throws Exception {
            mvc.perform(get("/api/auth/session"))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                    .andExpect(header().string(
                                    "Content-Security-Policy",
                                    "default-src 'none'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'"))
                    .andExpect(header().string("Referrer-Policy", "no-referrer"))
                    .andExpect(header().string("Cache-Control", containsString("no-store")));
        }

        @Test
        void theAuthorizationEndpoint_too() throws Exception {
            mvc.perform(authorize(new MockHttpSession(), REDIRECT))
                    .andExpect(header().string("X-Frame-Options", "DENY"))
                    .andExpect(header().string("Content-Security-Policy", containsString("frame-ancestors 'none'")));
        }
    }

    @Nested
    class ErrorBodies {

        @Test
        void aMalformedBody_revealsNoInternals() throws Exception {
            postJson("/api/auth/sign-in", new MockHttpSession(), "{\"identifier\":")
                    .andExpect(status().isBadRequest())
                    .andExpect(content().string(not(containsString("Exception"))))
                    .andExpect(content().string(not(containsString("jackson"))))
                    .andExpect(content().string(not(containsString("ca.northline"))));
        }

        @Test
        void anOversizedIdentifier_isAFieldError_notStoredInTheSession() throws Exception {
            var session = new MockHttpSession();
            postJson("/api/auth/sign-in", session, json(Map.of("identifier", "a".repeat(400) + "@example.ca")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("identifier"))
                    .andExpect(jsonPath("$.errors[0].rule").value("length"));
            assertThat(session.getAttributeNames().hasMoreElements()).isFalse();
        }

        @Test
        void unknownAccount_andWrongCode_lookTheSame() throws Exception {
            var user = register(newPerson());
            var known = new MockHttpSession();
            postJson(
                    "/api/auth/sign-in",
                    known,
                    json(Map.of("identifier", user.person().email())));
            var unknown = new MockHttpSession();
            postJson(
                    "/api/auth/sign-in",
                    unknown,
                    json(Map.of("identifier", "nobody-" + System.nanoTime() + "@example.ca")));
            for (var path : new String[] {"/api/auth/sign-in/totp", "/api/auth/sign-in/backup-code"}) {
                var body = json(Map.of("code", "000000"));
                var a = postJson(path, known, body).andReturn().getResponse();
                var b = postJson(path, unknown, body).andReturn().getResponse();
                assertThat(a.getStatus()).isEqualTo(b.getStatus()).isEqualTo(422);
                assertThat(a.getContentAsString()).isEqualTo(b.getContentAsString());
            }
        }
    }

    @Nested
    class OAuth {

        @Test
        void redirectUris_mustMatchExactly() throws Exception {
            var session = signIn(register(newPerson()));
            for (var uri : new String[] {
                REDIRECT + "/",
                REDIRECT + "?next=/x",
                REDIRECT + "x",
                "http://localhost:3101/login/oauth2/code/studio",
                "https://localhost:3100/login/oauth2/code/studio",
                "http://evil.example/login/oauth2/code/studio",
                "http://localhost:3100.evil.example/login/oauth2/code/studio"
            }) {
                var location = mvc.perform(authorize(session, uri))
                        .andReturn()
                        .getResponse()
                        .getRedirectedUrl();
                assertThat(location)
                        .as(uri)
                        .satisfiesAnyOf(
                                l -> assertThat(l).isNull(), l -> assertThat(l).doesNotStartWith(uri));
            }
        }

        @Test
        void pkceIsRequired_andOnlyS256() throws Exception {
            var session = signIn(register(newPerson()));
            var withoutPkce = mvc.perform(get("/oauth2/authorize")
                            .session(session)
                            .accept(MediaType.TEXT_HTML)
                            .queryParam("response_type", "code")
                            .queryParam("client_id", "studio-bff")
                            .queryParam("scope", "openid profile merchant")
                            .queryParam("redirect_uri", REDIRECT)
                            .queryParam("state", "s20"))
                    .andReturn()
                    .getResponse();
            assertNoCode(withoutPkce);
            var plain = mvc.perform(get("/oauth2/authorize")
                            .session(session)
                            .accept(MediaType.TEXT_HTML)
                            .queryParam("response_type", "code")
                            .queryParam("client_id", "studio-bff")
                            .queryParam("scope", "openid profile merchant")
                            .queryParam("redirect_uri", REDIRECT)
                            .queryParam("state", "s20")
                            .queryParam("code_challenge", VERIFIER)
                            .queryParam("code_challenge_method", "plain"))
                    .andReturn()
                    .getResponse();
            assertNoCode(plain);
        }

        /** Refused: a 400, or back to the client with {@code error=} — never with a code. */
        private static void assertNoCode(org.springframework.mock.web.MockHttpServletResponse response) {
            var location = response.getRedirectedUrl();
            if (location == null) {
                assertThat(response.getStatus()).isEqualTo(400);
            } else {
                assertThat(location).contains("error=").doesNotContain("code=");
            }
        }

        @Test
        void tokensLiveTenMinutes_andRefreshTokensRotate() throws Exception {
            var tokens = tokenResponse(signIn(register(newPerson())));
            var access = claims(JsonPath.read(tokens, "$.access_token"));
            assertThat(((Number) access.get("exp")).longValue() - ((Number) access.get("iat")).longValue())
                    .isEqualTo(600);
            assertThat(access.get("acr")).isEqualTo("mfa");
            String first = JsonPath.read(tokens, "$.refresh_token");
            String second = JsonPath.read(
                    refresh(first)
                            .andExpect(status().isOk())
                            .andReturn()
                            .getResponse()
                            .getContentAsString(),
                    "$.refresh_token");
            assertThat(second).isNotEqualTo(first);
            refresh(first)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            refresh(second).andExpect(status().isOk());
        }

        @Test
        void theBffRevokingItsRefreshToken_endsTheAuthSessionToo() throws Exception {
            var user = register(newPerson());
            var session = signIn(user);
            String refresh = JsonPath.read(tokenResponse(session), "$.refresh_token");

            mvc.perform(post("/oauth2/revoke")
                            .with(httpBasic("studio-bff", "dev-studio-bff"))
                            .param("token", refresh)
                            .param("token_type_hint", "refresh_token"))
                    .andExpect(status().isOk());

            // No "Not you?" call to /api/auth/sign-out was needed: the next /bff/login can't sign back in silently.
            mvc.perform(authorize(session, REDIRECT))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl(STUDIO + "/sign-in"));
            mvc.perform(get("/api/auth/session").session(session)).andExpect(status().isUnauthorized());
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM identity.sessions WHERE user_id = :u AND revoke_reason = 'signed_out'")
                            .param("u", user.userId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
            // The registration's own session is untouched.
            mvc.perform(get("/api/auth/session").session(user.session())).andExpect(status().isOk());
        }
    }
}
