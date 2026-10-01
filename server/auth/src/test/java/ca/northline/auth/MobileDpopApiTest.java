package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.DpopKey;
import com.jayway.jsonpath.JsonPath;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-29: the consumer and courier apps — public clients with PKCE, DPoP-bound access and refresh tokens (RFC 9449),
 * server nonces, single-use proofs, rotating refresh tokens with reuse detection, and their sign-ins in Settings ›
 * Security. Each app key is a freshly generated P-256 key ({@link DpopKey}).
 */
class MobileDpopApiTest extends AuthIntegrationTest {

    private static final String TOKEN_URL = "http://localhost/oauth2/token";
    private static final String APP = "mobile-consumer";
    private static final String APP_REDIRECT = "ca.northline.app:/oauth2redirect";
    private static final String APP_SCOPES = "openid profile orders bookings offline_access";
    private static final String COURIER = "courier-app";
    private static final String COURIER_REDIRECT = "ca.northline.courier:/oauth2redirect";
    private static final String VERIFIER = "s29-verifier-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFGH";

    /** What the app holds after a token response. */
    private record Tokens(
            String access, String refresh, @Nullable String idToken, String nonce) {}

    /** A person signed in on their phone's browser (a sign-in of its own), with the app's tokens. */
    private record Device(Registered user, MockHttpSession browser, DpopKey key, Tokens tokens) {
        String sessionId() {
            return String.valueOf(claims(tokens.idToken()).get("sid"));
        }
    }

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpSession signIn(Registered user, MockHttpSession session) throws Exception {
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        postJson(
                        "/api/auth/sign-in",
                        session,
                        json(Map.of("identifier", user.person().email())))
                .andExpect(status().isOk());
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isOk());
        return session;
    }

    private MockHttpServletRequestBuilder authorize(
            MockHttpSession session, String clientId, String redirectUri, String scopes) throws Exception {
        return get("/oauth2/authorize")
                .session(session)
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scopes)
                .queryParam("redirect_uri", redirectUri)
                .queryParam("state", "s29")
                .queryParam("code_challenge", challenge())
                .queryParam("code_challenge_method", "S256");
    }

    private String code(MockHttpSession session, String clientId, String redirectUri, String scopes) throws Exception {
        var location = mvc.perform(authorize(session, clientId, redirectUri, scopes))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(redirectUri + "?code=");
        return codeOf(location);
    }

    private static String codeOf(@Nullable String location) {
        return String.valueOf(UriComponentsBuilder.fromUriString(String.valueOf(location))
                .build()
                .getQueryParams()
                .getFirst("code"));
    }

    private ResultActions exchange(String clientId, String redirectUri, String code, @Nullable String proof)
            throws Exception {
        var request = post(TOKEN_URL)
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", redirectUri)
                .param("code_verifier", VERIFIER);
        return mvc.perform(proof == null ? request : request.header("DPoP", proof));
    }

    private ResultActions refresh(String clientId, String refreshToken, @Nullable String proof) throws Exception {
        var request = post(TOKEN_URL)
                .param("grant_type", "refresh_token")
                .param("client_id", clientId)
                .param("refresh_token", refreshToken);
        return mvc.perform(proof == null ? request : request.header("DPoP", proof));
    }

    /** The app's code exchange as RFC 9449 § 8 has it: the first proof has no nonce, the answer says which to use. */
    private Tokens tokens(String clientId, String redirectUri, String code, DpopKey key) throws Exception {
        var challenge = exchange(clientId, redirectUri, code, key.proof("POST", TOKEN_URL, null))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("use_dpop_nonce"))
                .andExpect(header().exists("DPoP-Nonce"))
                .andReturn()
                .getResponse();
        return read(exchange(clientId, redirectUri, code, key.proof("POST", TOKEN_URL, nonce(challenge)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("DPoP"))
                .andReturn()
                .getResponse());
    }

    private static Tokens read(MockHttpServletResponse response) throws Exception {
        var body = response.getContentAsString();
        List<String> idToken = JsonPath.read(body, "$..id_token");
        return new Tokens(
                JsonPath.read(body, "$.access_token"),
                JsonPath.read(body, "$.refresh_token"),
                idToken.isEmpty() ? null : idToken.getFirst(),
                nonce(response));
    }

    private static String nonce(MockHttpServletResponse response) {
        return String.valueOf(response.getHeader("DPoP-Nonce"));
    }

    private Device device(String clientId, String redirectUri, String scopes) throws Exception {
        var user = register(newPerson());
        var browser = signIn(user, new MockHttpSession());
        var key = DpopKey.generate();
        return new Device(
                user, browser, key, tokens(clientId, redirectUri, code(browser, clientId, redirectUri, scopes), key));
    }

    private Device app() throws Exception {
        return device(APP, APP_REDIRECT, APP_SCOPES);
    }

    private Tokens refreshed(Device device, Tokens from) throws Exception {
        return read(refresh(APP, from.refresh(), device.key().proof("POST", TOKEN_URL, from.nonce()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.token_type").value("DPoP"))
                .andReturn()
                .getResponse());
    }

    private static Map<String, Object> claims(@Nullable String jwt) {
        return JsonPath.read(
                new String(Base64.getUrlDecoder().decode(String.valueOf(jwt).split("\\.")[1]), StandardCharsets.UTF_8),
                "$");
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }

    private @Nullable String revokeReason(String sessionId) {
        return jdbc.sql("SELECT revoke_reason FROM identity.sessions WHERE id = :id")
                .param("id", sessionId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    private long audit(String userId, String action) {
        return jdbc.sql("SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = :a")
                .param("u", userId)
                .param("a", action)
                .query(Long.class)
                .single();
    }

    @Nested
    class DpopBoundTokens {

        @Test
        @SuppressWarnings("unchecked")
        void theWholeFlow_givesDpopBoundTokens_whoseRefreshRotates_underTheSameKey() throws Exception {
            var device = app();
            var access = claims(device.tokens().access());
            assertThat(access.get("cnf")).isEqualTo(Map.of("jkt", device.key().thumbprint()));
            assertThat(access.get("sub")).isEqualTo(device.user().userId());
            assertThat((List<Object>) access.get("aud")).containsExactlyInAnyOrder(APP, "northline-api");
            assertThat(String.valueOf(access.get("scope")).split(" ")).containsExactlyInAnyOrder(APP_SCOPES.split(" "));
            assertThat(access.get("acr")).isEqualTo("mfa"); // a consumer token needn't have it; this sign-in did
            assertThat(device.tokens().refresh()).isNotBlank();

            var next = refreshed(device, device.tokens());
            assertThat(next.refresh()).isNotEqualTo(device.tokens().refresh());
            assertThat(claims(next.access()).get("cnf"))
                    .isEqualTo(Map.of("jkt", device.key().thumbprint()));
            assertThat(claims(next.access()).get("sub")).isEqualTo(device.user().userId());
            refreshed(device, next);
        }

        @Test
        void aPublicClient_withoutAProof_getsNothing_butLearnsTheNonce() throws Exception {
            var user = register(newPerson());
            var code = code(signIn(user, new MockHttpSession()), APP, APP_REDIRECT, APP_SCOPES);
            exchange(APP, APP_REDIRECT, code, null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"))
                    .andExpect(header().exists("DPoP-Nonce"));
            // The code wasn't spent: the app retries properly.
            assertThat(tokens(APP, APP_REDIRECT, code, DpopKey.generate()).refresh())
                    .isNotBlank();
        }

        @Test
        void aRefresh_withoutAProof_isRefused() throws Exception {
            var device = app();
            refresh(APP, device.tokens().refresh(), null)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"));
            refreshed(device, device.tokens()); // nothing was spent
        }

        @Test
        void aReplayedProof_isRefused() throws Exception {
            var device = app();
            var proof = device.key().proof("POST", TOKEN_URL, device.tokens().nonce());
            var next = read(refresh(APP, device.tokens().refresh(), proof)
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse());
            refresh(APP, next.refresh(), proof)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"));
            refreshed(device, next); // a fresh proof still works
        }

        @Test
        void aProofFromAnotherKey_isRefused_andSpendsNothing() throws Exception {
            var device = app();
            refresh(
                            APP,
                            device.tokens().refresh(),
                            DpopKey.generate()
                                    .proof("POST", TOKEN_URL, device.tokens().nonce()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"));
            refreshed(device, device.tokens());
            assertThat(revokeReason(device.sessionId())).isNull();
        }

        @Test
        void aProofForAnotherUrlOrMethod_orWithAStaleNonce_isRefused() throws Exception {
            var device = app();
            var nonce = device.tokens().nonce();
            refresh(APP, device.tokens().refresh(), device.key().proof("POST", "http://localhost/oauth2/revoke", nonce))
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"));
            refresh(APP, device.tokens().refresh(), device.key().proof("GET", TOKEN_URL, nonce))
                    .andExpect(jsonPath("$.error").value("invalid_dpop_proof"));
            refresh(APP, device.tokens().refresh(), device.key().proof("POST", TOKEN_URL, "made-up"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("use_dpop_nonce"));
            clock.advanceSeconds(Duration.ofMinutes(11).toSeconds()); // two nonce windows later
            refresh(APP, device.tokens().refresh(), device.key().proof("POST", TOKEN_URL, nonce))
                    .andExpect(jsonPath("$.error").value("use_dpop_nonce"));
        }

        @Test
        void theStudioBff_stillGetsBearerTokens_withoutDpop() throws Exception {
            var user = register(newPerson());
            var code = code(
                    signIn(user, new MockHttpSession()),
                    "studio-bff",
                    "http://localhost:3100/login/oauth2/code/studio",
                    "openid profile merchant");
            mvc.perform(post(TOKEN_URL)
                            .with(httpBasic("studio-bff", "dev-studio-bff"))
                            .param("grant_type", "authorization_code")
                            .param("code", code)
                            .param("redirect_uri", "http://localhost:3100/login/oauth2/code/studio")
                            .param("code_verifier", VERIFIER))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.token_type").value("Bearer"))
                    .andExpect(header().doesNotExist("DPoP-Nonce"));
        }
    }

    @Nested
    class ReuseDetection {

        @Test
        void aRotatedRefreshToken_presentedAgain_revokesTheFamily_andEndsTheSignIn() throws Exception {
            var device = app();
            var first = device.tokens();
            var second = refreshed(device, first);

            refresh(APP, first.refresh(), device.key().proof("POST", TOKEN_URL, second.nonce()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));

            // The whole family is gone: the current token too.
            refresh(APP, second.refresh(), device.key().proof("POST", TOKEN_URL, second.nonce()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            assertThat(revokeReason(device.sessionId())).isEqualTo("refresh_token_reused");
            assertThat(audit(device.user().userId(), "auth.refresh_token_reused"))
                    .isEqualTo(1);
            assertThat(audit(device.user().userId(), "auth.session_revoked")).isEqualTo(1);
            // The phone's browser session is signed out too; the person's other sign-in isn't.
            mvc.perform(get("/api/auth/session").session(device.browser())).andExpect(status().isUnauthorized());
            mvc.perform(get("/api/auth/session").session(device.user().session()))
                    .andExpect(status().isOk());
            assertThat(jdbc.sql("SELECT count(*) FROM auth.issued_refresh_tokens t JOIN auth.oauth2_authorization a"
                                    + " ON a.id = t.authorization_id WHERE a.principal_name = :u")
                            .param("u", device.user().userId())
                            .query(Long.class)
                            .single())
                    .isZero();
        }

        @Test
        void onlyHashesAreStored() throws Exception {
            var device = app();
            var stored = jdbc.sql("SELECT token_hash FROM auth.issued_refresh_tokens t JOIN auth.oauth2_authorization a"
                            + " ON a.id = t.authorization_id WHERE a.principal_name = :u")
                    .param("u", device.user().userId())
                    .query(String.class)
                    .list();
            assertThat(stored).hasSize(1).doesNotContain(device.tokens().refresh());
        }

        @Test
        void theAppSigningOut_revokesItsRefreshToken_andEndsTheSignIn_likeTheStudio() throws Exception {
            var device = app();
            mvc.perform(post("http://localhost/oauth2/revoke")
                            .param("client_id", APP)
                            .param("token", device.tokens().refresh())
                            .param("token_type_hint", "refresh_token"))
                    .andExpect(status().isOk());
            assertThat(revokeReason(device.sessionId())).isEqualTo("signed_out");
            refresh(
                            APP,
                            device.tokens().refresh(),
                            device.key()
                                    .proof("POST", TOKEN_URL, device.tokens().nonce()))
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            // Public clients can revoke, not introspect.
            mvc.perform(post("http://localhost/oauth2/introspect")
                            .accept(MediaType.APPLICATION_JSON)
                            .param("client_id", APP)
                            .param("token", device.tokens().access()))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Sessions {

        @Test
        void thePhonesSignIn_isListedWithTheApp_andRevokingItStopsTheRefreshTokens() throws Exception {
            var device = app();
            var body = mvc.perform(
                            get("/api/auth/security").session(device.user().session()))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<List<String>> apps = JsonPath.read(body, "$.sessions[?(@.id == '" + device.sessionId() + "')].apps");
            assertThat(apps).containsExactly(List.of("Northline"));

            postJson(
                            "/api/auth/security/sessions/" + device.sessionId() + "/revoke",
                            device.user().session(),
                            "{}")
                    .andExpect(status().isOk());

            refresh(
                            APP,
                            device.tokens().refresh(),
                            device.key()
                                    .proof("POST", TOKEN_URL, device.tokens().nonce()))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            assertThat(revokeReason(device.sessionId())).isEqualTo("revoked");
        }

        @Test
        void theAppsSignInPage_goesBackToTheAppsAuthorizationRequest() throws Exception {
            var user = register(newPerson());
            var browser = new MockHttpSession();
            mvc.perform(authorize(browser, APP, APP_REDIRECT, APP_SCOPES))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost:3100/sign-in"));

            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            postJson(
                            "/api/auth/sign-in",
                            browser,
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            var body = postJson("/api/auth/sign-in/totp", browser, json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.continueTo", startsWith("http://localhost/oauth2/authorize?")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String continueTo = JsonPath.read(body, "$.continueTo");

            var location = mvc.perform(
                            get(URI.create(continueTo)).session(browser).accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andReturn()
                    .getResponse()
                    .getRedirectedUrl();
            assertThat(location).startsWith(APP_REDIRECT + "?code=").contains("state=s29");
            assertThat(tokens(APP, APP_REDIRECT, codeOf(location), DpopKey.generate())
                            .access())
                    .isNotBlank();
        }

        @Test
        void theStudiosOwnSignIn_hasNothingToGoBackTo() throws Exception {
            var user = register(newPerson());
            var browser = new MockHttpSession();
            mvc.perform(authorize(browser, "studio-bff", "http://localhost:3100/login/oauth2/code/studio", "openid"))
                    .andExpect(status().is3xxRedirection());
            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            postJson(
                            "/api/auth/sign-in",
                            browser,
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            postJson("/api/auth/sign-in/totp", browser, json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.continueTo").doesNotExist());
        }
    }

    @Nested
    class Courier {

        @Test
        void theCourierApp_getsDeviceBoundTokens_withAShortRefresh() throws Exception {
            var device = device(COURIER, COURIER_REDIRECT, "openid courier deliveries");
            var access = claims(device.tokens().access());
            assertThat(access.get("cnf")).isEqualTo(Map.of("jkt", device.key().thumbprint()));
            assertThat(String.valueOf(access.get("scope")).split(" "))
                    .containsExactlyInAnyOrder("openid", "courier", "deliveries");
            var lifetime = jdbc.sql("""
                            SELECT refresh_token_issued_at, refresh_token_expires_at FROM auth.oauth2_authorization
                             WHERE principal_name = :u""")
                    .param("u", device.user().userId())
                    .query((rs, _) -> Duration.between(
                            rs.getObject(1, OffsetDateTime.class), rs.getObject(2, OffsetDateTime.class)))
                    .single();
            assertThat(lifetime).isEqualTo(Duration.ofHours(12));
        }

        @Test
        void theCourierApp_signsInOnTheConsumerSitesPage_notTheStudios_andGoesBackToTheApp() throws Exception {
            var user = register(newPerson());
            var browser = new MockHttpSession();
            mvc.perform(authorize(browser, COURIER, COURIER_REDIRECT, "openid courier deliveries"))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost:3000/sign-in"));

            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            postJson(
                            "/api/auth/sign-in",
                            browser,
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            var body = postJson("/api/auth/sign-in/totp", browser, json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.continueTo", startsWith("http://localhost/oauth2/authorize?")))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var location = mvc.perform(get(URI.create(JsonPath.read(body, "$.continueTo")))
                            .session(browser)
                            .accept(MediaType.TEXT_HTML))
                    .andExpect(status().is3xxRedirection())
                    .andReturn()
                    .getResponse()
                    .getRedirectedUrl();
            assertThat(location).startsWith(COURIER_REDIRECT + "?code=").contains("state=s29");
        }

        @Test
        void theConsumerApp_cannotAskForCourierScopes() throws Exception {
            var user = register(newPerson());
            var location = mvc.perform(
                            authorize(signIn(user, new MockHttpSession()), APP, APP_REDIRECT, "openid courier"))
                    .andReturn()
                    .getResponse()
                    .getRedirectedUrl();
            assertThat(location).contains("error=invalid_scope").doesNotContain("code=");
        }
    }
}
