package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SoftAuthenticator;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-19 session management through the API: active sessions with the current flag, revoking one or all others (refresh
 * tokens die, the auth session can't re-authorize silently, the BFF's introspection sees "inactive"), removing a passkey
 * but never the last second factor, step-up (a second factor from the last 10 minutes) and the audit log.
 */
class SessionManagementApiTest extends AuthIntegrationTest {

    private static final String ORIGIN = "http://localhost:3100";
    private static final String REDIRECT = "http://localhost:3100/login/oauth2/code/studio";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-northline-s19";
    private static final String MAC = "Mozilla/5.0 (Macintosh; Intel Mac OS X 14_5)";
    private static final String IPHONE = "Mozilla/5.0 (iPhone; CPU iPhone OS 18_0 like Mac OS X)";

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    /** A second sign-in of the same person (another device), with the authenticator app. */
    private MockHttpSession signInAgain(Registered user, String userAgent) throws Exception {
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        var session = new MockHttpSession();
        postJson(
                        "/api/auth/sign-in",
                        session,
                        json(Map.of("identifier", user.person().email())))
                .andExpect(status().isOk());
        mvc.perform(post("/api/auth/sign-in/totp")
                        .session(session)
                        .header("User-Agent", userAgent)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(user.totpSecret())))))
                .andExpect(status().isOk());
        return session;
    }

    private String sessionIdOf(MockHttpSession session) throws Exception {
        var body = mvc.perform(get("/api/auth/security").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        List<String> current = JsonPath.read(body, "$.sessions[?(@.current == true)].id");
        assertThat(current).hasSize(1);
        return current.getFirst();
    }

    private ResultActions revoke(MockHttpSession from, String sessionId) throws Exception {
        return mvc.perform(post("/api/auth/security/sessions/{id}/revoke", sessionId)
                .session(from)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{}"));
    }

    private long audit(String userId, String action) {
        return jdbc.sql("SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = :a")
                .param("u", userId)
                .param("a", action)
                .query(Long.class)
                .single();
    }

    /** The studio-bff's authorization code flow from this auth session: the tokens the BFF would keep. */
    private Map<String, String> bffTokens(MockHttpSession session) throws Exception {
        var location = mvc.perform(authorize(session))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT);
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        var body = mvc.perform(post("/oauth2/token")
                        .with(httpBasic("studio-bff", "dev-studio-bff"))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Map.of(
                "refresh_token", JsonPath.read(body, "$.refresh_token"),
                "id_token", JsonPath.read(body, "$.id_token"));
    }

    private MockHttpServletRequestBuilder authorize(MockHttpSession session) throws Exception {
        var challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize")
                .session(session)
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", "studio-bff")
                .queryParam("scope", "openid profile merchant")
                .queryParam("redirect_uri", REDIRECT)
                .queryParam("state", "s1")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    private ResultActions refresh(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/token")
                .with(httpBasic("studio-bff", "dev-studio-bff"))
                .param("grant_type", "refresh_token")
                .param("refresh_token", refreshToken));
    }

    private ResultActions introspect(String refreshToken) throws Exception {
        return mvc.perform(post("/oauth2/introspect")
                .with(httpBasic("studio-bff", "dev-studio-bff"))
                .param("token", refreshToken)
                .param("token_type_hint", "refresh_token"));
    }

    private static String payload(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    @Nested
    class Listing {

        @Test
        void listsActiveSessions_withDevice_approximateIp_apps_andTheCurrentOne() throws Exception {
            var user = register(newPerson());
            var phone = signInAgain(user, IPHONE);
            bffTokens(phone);

            var body = mvc.perform(get("/api/auth/security").session(user.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions", hasSize(2)))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            List<Map<String, Object>> sessions = JsonPath.read(body, "$.sessions");
            var mine = sessions.stream()
                    .filter(s -> Boolean.TRUE.equals(s.get("current")))
                    .toList();
            var other = sessions.stream()
                    .filter(s -> Boolean.FALSE.equals(s.get("current")))
                    .toList();
            assertThat(mine)
                    .singleElement()
                    .satisfies(s -> assertThat(s.get("method")).isEqualTo("registration"));
            assertThat(other).singleElement().satisfies(s -> {
                assertThat(s.get("device")).isEqualTo(IPHONE);
                assertThat(s.get("method")).isEqualTo("totp");
                assertThat(s.get("ipApprox")).isEqualTo("127.0.0.x");
                assertThat(s.get("apps"))
                        .asInstanceOf(org.assertj.core.api.InstanceOfAssertFactories.LIST)
                        .isNotEmpty();
                assertThat(s.get("lastSeenAt")).isNotNull();
            });
        }

        @Test
        void theBffSessionsSid_isInTheIdToken_andCountsAsCurrentWhenPassed() throws Exception {
            var user = register(newPerson());
            var bff = signInAgain(user, MAC);
            var sid = sessionIdOf(bff);
            assertThat(JsonPath.<String>read(payload(bffTokens(bff).get("id_token")), "$.sid"))
                    .isEqualTo(sid);

            mvc.perform(get("/api/auth/security").session(user.session()).queryParam("current", sid))
                    .andExpect(jsonPath("$.sessions[?(@.current == true)]", hasSize(2)));
        }

        @Test
        void signingOut_endsTheSession_andItsRefreshTokens() throws Exception {
            var user = register(newPerson());
            var other = signInAgain(user, MAC);
            var tokens = bffTokens(other);

            mvc.perform(post("/api/auth/sign-out").session(other)).andExpect(status().isNoContent());

            mvc.perform(get("/api/auth/security").session(user.session()))
                    .andExpect(jsonPath("$.sessions", hasSize(1)));
            refresh(tokens.get("refresh_token")).andExpect(status().isBadRequest());
        }
    }

    @Nested
    class Revoking {

        @Test
        void aRevokedSession_cantRefresh_isInactive_andCantSilentlyReauthorize() throws Exception {
            var user = register(newPerson());
            var other = signInAgain(user, IPHONE);
            var otherId = sessionIdOf(other);
            var tokens = bffTokens(other);
            refresh(tokens.get("refresh_token")).andExpect(status().isOk()); // works before
            var rotated = bffTokens(other).get("refresh_token");

            revoke(user.session(), otherId)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.sessions", hasSize(1)))
                    .andExpect(jsonPath("$.sessions[0].current").value(true));

            refresh(rotated)
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error").value("invalid_grant"));
            introspect(rotated)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.active").value(false));
            // The auth session is dropped on its next request: no silent code for the BFF, no JSON API.
            mvc.perform(authorize(other))
                    .andExpect(status().is3xxRedirection())
                    .andExpect(redirectedUrl("http://localhost:3100/sign-in"));
            mvc.perform(get("/api/auth/security").session(other)).andExpect(status().isUnauthorized());
            assertThat(audit(user.userId(), "auth.session_revoked")).isEqualTo(1);
            assertThat(jdbc.sql("SELECT revoke_reason FROM identity.sessions WHERE id = :id")
                            .param("id", otherId)
                            .query(String.class)
                            .single())
                    .isEqualTo("revoked");
        }

        @Test
        void theCurrentSession_isntRevokedHere_andSomeoneElsesIsNotFound() throws Exception {
            var user = register(newPerson());
            var stranger = register(newPerson());
            revoke(user.session(), sessionIdOf(user.session()))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("current_session"));
            revoke(user.session(), sessionIdOf(stranger.session()))
                    .andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("not_found"));
            mvc.perform(get("/api/auth/security").session(stranger.session())).andExpect(status().isOk());
        }

        @Test
        void revokeOthers_keepsThisSession_andTheBffOne() throws Exception {
            var user = register(newPerson());
            var bff = signInAgain(user, MAC);
            var bffId = sessionIdOf(bff);
            var bffRefresh = bffTokens(bff).get("refresh_token");
            var phone = signInAgain(user, IPHONE);
            var phoneRefresh = bffTokens(phone).get("refresh_token");
            var tablet = signInAgain(user, IPHONE);

            postJson("/api/auth/security/sessions/revoke-others", user.session(), json(Map.of("current", bffId)))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.revoked").value(2));

            mvc.perform(get("/api/auth/security").session(user.session()))
                    .andExpect(jsonPath("$.sessions[*].id", containsInAnyOrder(sessionIdOf(user.session()), bffId)));
            refresh(bffRefresh).andExpect(status().isOk());
            refresh(phoneRefresh).andExpect(status().isBadRequest());
            mvc.perform(get("/api/auth/security").session(tablet)).andExpect(status().isUnauthorized());
            assertThat(audit(user.userId(), "auth.session_revoked")).isEqualTo(2);
        }
    }

    @Nested
    class StepUp {

        @Test
        void changesNeedASecondFactorFromTheLastTenMinutes_andStepUpRenewsIt() throws Exception {
            var user = register(newPerson());
            var other = signInAgain(user, IPHONE);
            var otherId = sessionIdOf(other);
            clock.advanceSeconds(11 * 60);

            revoke(user.session(), otherId)
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value("step_up_required"))
                    .andExpect(jsonPath("$.detail").value("Confirm it's you to make this change."));
            postJson("/api/auth/security/sessions/revoke-others", user.session(), "{}")
                    .andExpect(status().isForbidden());
            mvc.perform(delete("/api/auth/security/passkeys/{id}", "any").session(user.session()))
                    .andExpect(status().isForbidden());

            postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk());
            revoke(user.session(), otherId).andExpect(status().isOk());
        }

        @Test
        void withoutASecondFactorSession_everythingIs401() throws Exception {
            var anonymous = new MockHttpSession();
            revoke(anonymous, "01J9ZD3V00000000000000XXXX").andExpect(status().isUnauthorized());
            mvc.perform(delete("/api/auth/security/passkeys/{id}", "x").session(anonymous))
                    .andExpect(status().isUnauthorized());
        }
    }

    @Nested
    class Passkeys {

        private String addKey(MockHttpSession session, String label) throws Exception {
            var options = mvc.perform(
                            post("/api/auth/security/passkeys/options").session(session))
                    .andExpect(status().isOk())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var key = new SoftAuthenticator(ORIGIN);
            postJson(
                            "/api/auth/security/passkeys",
                            session,
                            "{\"credential\":" + key.create(options) + ",\"label\":\"" + label + "\"}")
                    .andExpect(status().isCreated());
            return key.credentialId();
        }

        private String mfaPrimary(String userId) {
            return jdbc.sql("SELECT mfa_primary FROM identity.users WHERE id = :u")
                    .param("u", userId)
                    .query(String.class)
                    .single();
        }

        @Test
        void aPasskeyCanGo_whileTheAuthenticatorRemains() throws Exception {
            var user = register(newPerson()); // authenticator app
            var id = addKey(user.session(), "YubiKey 5C");
            mvc.perform(delete("/api/auth/security/passkeys/{id}", id).session(user.session()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passkeys", hasSize(0)));
            assertThat(mfaPrimary(user.userId())).isEqualTo("totp");
            assertThat(audit(user.userId(), "auth.passkey_removed")).isEqualTo(1);
            mvc.perform(delete("/api/auth/security/passkeys/{id}", id).session(user.session()))
                    .andExpect(status().isNotFound());
        }

        @Test
        void theLastSecondFactor_cantBeRemoved() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                    .andExpect(status().isOk());
            var creation = mvc.perform(
                            post("/api/auth/register/passkey/options").session(session))
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            var key = new SoftAuthenticator(ORIGIN);
            var created = postJson(
                            "/api/auth/register/passkey", session, "{\"credential\":" + key.create(creation) + "}")
                    .andExpect(status().isCreated())
                    .andReturn()
                    .getResponse()
                    .getContentAsString();
            String userId = JsonPath.read(created, "$.user.id");

            mvc.perform(delete("/api/auth/security/passkeys/{id}", key.credentialId())
                            .session(session))
                    .andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("last_factor"));

            var second = addKey(session, "Security key");
            mvc.perform(delete("/api/auth/security/passkeys/{id}", key.credentialId())
                            .session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.passkeys[*].id", containsInAnyOrder(second)));
            mvc.perform(delete("/api/auth/security/passkeys/{id}", second).session(session))
                    .andExpect(status().isConflict());
            assertThat(mfaPrimary(userId)).isEqualTo("passkey");
        }

        @Test
        void someoneElsesPasskey_isNotFound() throws Exception {
            var owner = register(newPerson());
            var id = addKey(owner.session(), "Owner key");
            var other = register(newPerson());
            mvc.perform(delete("/api/auth/security/passkeys/{id}", id).session(other.session()))
                    .andExpect(status().isNotFound());
            mvc.perform(get("/api/auth/security").session(owner.session()))
                    .andExpect(jsonPath("$.passkeys", hasSize(1)));
        }
    }
}
