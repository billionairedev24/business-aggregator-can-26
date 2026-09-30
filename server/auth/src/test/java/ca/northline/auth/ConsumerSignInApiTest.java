package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
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

/**
 * S-62, the consumer site's sign-in and registration (design 06 `auth`): a code to the phone is enough (no second
 * factor, no {@code acr=mfa}); unknown accounts look the same; registration may finish without a second factor; such a
 * session gets consumer-bff codes but never Studio ones; the consumer-bff's unauthenticated authorization requests land
 * on the consumer sign-in page.
 */
class ConsumerSignInApiTest extends AuthIntegrationTest {

    private static final String VERIFIER = "s62-verifier-0123456789-abcdefghijklmnopqrstuvwxyz-ABCDEFG";

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpSession started(String identifier) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/sign-in", session, json(Map.of("identifier", identifier)))
                .andExpect(status().isOk());
        return session;
    }

    /** Signs in with a code to the phone; returns the auth session. */
    private MockHttpSession signInByCode(Registered user) throws Exception {
        var session = started(user.person().phone());
        postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
        postJson(
                        "/api/auth/sign-in/code/verify",
                        session,
                        json(Map.of("code", sms.lastCodeTo(user.person().e164()))))
                .andExpect(status().isOk());
        return session;
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }

    private MockHttpServletRequestBuilder authorize(
            MockHttpSession session, String client, String redirect, String scope) throws Exception {
        return get("/oauth2/authorize")
                .session(session)
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", client)
                .queryParam("scope", scope)
                .queryParam("redirect_uri", redirect)
                .queryParam("state", "s62")
                .queryParam("code_challenge", challenge())
                .queryParam("code_challenge_method", "S256");
    }

    private MockHttpServletRequestBuilder consumerAuthorize(MockHttpSession session) throws Exception {
        return authorize(
                session, "consumer-bff", "http://localhost:8081/login/oauth2/code/northline", "openid profile orders");
    }

    private MockHttpServletRequestBuilder studioAuthorize(MockHttpSession session) throws Exception {
        return authorize(
                session, "studio-bff", "http://localhost:3100/login/oauth2/code/studio", "openid profile merchant");
    }

    @Nested
    class SignInByCode {

        @Test
        void aCodeToThePhone_signsIn_withoutAcr() throws Exception {
            var user = register(newPerson());
            var session = started(user.person().phone());
            postJson("/api/auth/sign-in/code", session, "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resendAfterSeconds").value(45))
                    .andExpect(jsonPath("$.channel").value("sms"));
            assertThat(sms.sentTo(user.person().e164())).hasSize(2); // registration + sign-in

            postJson(
                            "/api/auth/sign-in/code/verify",
                            session,
                            json(Map.of("code", sms.lastCodeTo(user.person().e164()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.user.id").value(user.userId()))
                    .andExpect(jsonPath("$.user.firstName").value("Amara"))
                    .andExpect(jsonPath("$.acr").doesNotExist());

            mvc.perform(get("/api/auth/session").session(session))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acr").doesNotExist());
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM identity.sessions WHERE user_id = :u AND method = 'phone_otp' AND acr IS NULL")
                            .param("u", user.userId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        }

        @Test
        void byEmail_theCodeGoesToTheAccountsPhone() throws Exception {
            var user = register(newPerson());
            var session = started(user.person().email());
            postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
            postJson(
                            "/api/auth/sign-in/code/verify",
                            session,
                            json(Map.of("code", sms.lastCodeTo(user.person().e164()))))
                    .andExpect(status().isOk());
        }

        @Test
        void anUnknownAccount_getsTheSameAnswers_andNoTextIsSent() throws Exception {
            var nobody = newPerson();
            var session = started(nobody.phone());
            postJson("/api/auth/sign-in/code", session, "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.resendAfterSeconds").value(45))
                    .andExpect(jsonPath("$.channel").value("sms"));
            assertThat(sms.sentTo(nobody.e164())).isEmpty();
            postJson("/api/auth/sign-in/code", session, "{}")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.code").value("otp_throttled"));
            postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", "123456")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"))
                    .andExpect(jsonPath("$.errors[0].message").value("That code didn't work. Check it and try again."));
        }

        @Test
        void resend_waits45s_callMeInsteadDoesNot() throws Exception {
            var user = register(newPerson());
            var session = started(user.person().phone());
            postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
            postJson("/api/auth/sign-in/code", session, "{\"channel\":\"sms\"}")
                    .andExpect(status().isTooManyRequests())
                    .andExpect(jsonPath("$.retryAfterSeconds").value(45));
            postJson("/api/auth/sign-in/code", session, "{\"channel\":\"voice\"}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.channel").value("voice"));
            clock.advanceSeconds(46);
            postJson("/api/auth/sign-in/code", session, "{}")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.channel").value("sms"));
        }

        @Test
        void wrongCodes_areCounted_untilANewCodeIsNeeded() throws Exception {
            var user = register(newPerson());
            var session = started(user.person().phone());
            postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
            var right = sms.lastCodeTo(user.person().e164());
            var wrong = right.equals("000000") ? "111111" : "000000";
            for (int i = 0; i < 4; i++) {
                postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", wrong)))
                        .andExpect(status().isUnprocessableContent())
                        .andExpect(jsonPath("$.errors[0].rule").value("mismatch"));
            }
            postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", wrong)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("locked"))
                    .andExpect(jsonPath("$.errors[0].message").value("Too many tries. Send a new code."));
            postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", right)))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].rule").value("locked"));
        }

        @Test
        void anExpiredCode_asksForANewOne() throws Exception {
            var user = register(newPerson());
            var session = started(user.person().phone());
            postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
            clock.advanceSeconds(11 * 60);
            postJson(
                            "/api/auth/sign-in/code/verify",
                            session,
                            json(Map.of("code", sms.lastCodeTo(user.person().e164()))))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("That code has expired. Send a new one."));
        }

        @Test
        void aCodeForAnEarlierIdentifier_doesNotCount() throws Exception {
            var user = register(newPerson());
            var other = register(newPerson());
            var session = started(user.person().phone());
            postJson("/api/auth/sign-in/code", session, "{}").andExpect(status().isOk());
            var code = sms.lastCodeTo(user.person().e164());
            postJson(
                            "/api/auth/sign-in",
                            session,
                            json(Map.of("identifier", other.person().phone())))
                    .andExpect(status().isOk());
            postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", code)))
                    .andExpect(status().isConflict());
        }

        @Test
        void withoutAStartedSignIn_isAConflict() throws Exception {
            postJson("/api/auth/sign-in/code", new MockHttpSession(), "{}").andExpect(status().isConflict());
            postJson("/api/auth/sign-in/code/verify", new MockHttpSession(), json(Map.of("code", "123456")))
                    .andExpect(status().isConflict());
        }

        @Test
        void theCodeIsValidated() throws Exception {
            var session = started(newPerson().phone());
            postJson("/api/auth/sign-in/code/verify", session, "{}")
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("Enter the 6-digit code."));
            postJson("/api/auth/sign-in/code/verify", session, json(Map.of("code", "12ab")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("The code is 6 digits."));
        }
    }

    @Nested
    class RegistrationWithoutSecondFactor {

        @Test
        void smsOnly_createsTheAccount_signedInWithoutAcr() throws Exception {
            var person = newPerson();
            var session = new MockHttpSession();
            postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
            postJson("/api/auth/register/complete", session, "{}").andExpect(status().isConflict()); // phone first
            postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.step").value("mfa"));
            postJson("/api/auth/register/complete", session, "{}")
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.user.phone").value(person.e164()))
                    .andExpect(jsonPath("$.acr").doesNotExist());

            var row = jdbc.sql("SELECT id, mfa_primary FROM identity.users WHERE email = CAST(:e AS citext)")
                    .param("e", person.email())
                    .query()
                    .singleRow();
            assertThat(row).containsEntry("mfa_primary", "sms");
            assertThat(jdbc.sql("SELECT acr FROM identity.sessions WHERE user_id = :u AND method = 'registration'")
                            .param("u", row.get("id"))
                            .query(String.class)
                            .optional())
                    .isEmpty();
        }
    }

    @Nested
    class Authorization {

        @Test
        void consumerBff_unauthenticated_goesToTheConsumerSignInPage() throws Exception {
            mvc.perform(consumerAuthorize(new MockHttpSession()))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("http://localhost:3000/sign-in"));
        }

        @Test
        void studioBff_unauthenticated_stillGoesToTheStudio() throws Exception {
            mvc.perform(studioAuthorize(new MockHttpSession()))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("http://localhost:3100/sign-in"));
        }

        @Test
        void aPhoneCodeSession_getsAConsumerCode_butNoStudioCode() throws Exception {
            var user = register(newPerson());
            var session = signInByCode(user);

            mvc.perform(consumerAuthorize(session))
                    .andExpect(status().isFound())
                    .andExpect(header().string(
                                    "Location", startsWith("http://localhost:8081/login/oauth2/code/northline?code=")));

            mvc.perform(studioAuthorize(session))
                    .andExpect(status().isFound())
                    .andExpect(redirectedUrl("http://localhost:3100/sign-in"));

            // Signing in again with the second factor (the Studio's sign-in) replaces the session: now the Studio gets
            // one.
            clock.advanceSeconds(Totp.PERIOD_SECONDS);
            postJson(
                            "/api/auth/sign-in",
                            session,
                            json(Map.of("identifier", user.person().email())))
                    .andExpect(status().isOk());
            postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.acr").value("mfa"));
            mvc.perform(studioAuthorize(session))
                    .andExpect(status().isFound())
                    .andExpect(header().string(
                                    "Location", startsWith("http://localhost:3100/login/oauth2/code/studio?code=")));
        }
    }
}
