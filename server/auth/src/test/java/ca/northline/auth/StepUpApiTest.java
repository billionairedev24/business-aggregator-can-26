package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SoftAuthenticator;
import com.jayway.jsonpath.JsonPath;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.ResultActions;

/** Step-up for payouts: a fresh passkey / authenticator confirmation returns a short-lived proof for the api. */
class StepUpApiTest extends AuthIntegrationTest {

    private static final String ORIGIN = "http://localhost:3100";

    @Autowired
    JwtDecoder decoder;

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    @Test
    void totp_returnsAProofForThisUser() throws Exception {
        var user = register(newPerson());
        var body = postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jwt = decoder.decode(JsonPath.read(body, "$.proof"));
        assertThat(jwt.getSubject()).isEqualTo(user.userId());
        assertThat(jwt.getAudience()).containsExactly("northline-api/step-up");
        assertThat(jwt.getClaimAsString("token_use")).isEqualTo("step_up");
        assertThat(jwt.getClaimAsString("acr")).isEqualTo("mfa");
        assertThat(jwt.getClaimAsStringList("amr")).containsExactly("otp");
        assertThat(jwt.getId()).isNotBlank();
        assertThat(jwt.getClaimAsInstant("auth_time")).isNotNull();
    }

    @Test
    void wrongCode_isRejected_andFiveFailuresLockStepUp() throws Exception {
        var user = register(newPerson());
        for (int i = 0; i < 4; i++) {
            postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].message").value("That code didn't work. Check it and try again."));
        }
        postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", "000000")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_attempts"));
    }

    @Test
    void usedCodeStep_cannotBeReplayed() throws Exception {
        var user = register(newPerson());
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        var code = totpNow(user.totpSecret());
        postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", code)))
                .andExpect(status().isOk());
        postJson("/api/auth/step-up/totp", user.session(), json(Map.of("code", code)))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void withoutASignedInSession_isUnauthorized() throws Exception {
        postJson("/api/auth/step-up/totp", new MockHttpSession(), json(Map.of("code", "123456")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        mvc.perform(post("/api/auth/step-up/passkey/options").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void passkey_returnsAProof() throws Exception {
        var person = newPerson();
        var session = new MockHttpSession();
        postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                .andExpect(status().isOk());
        var creation = mvc.perform(post("/api/auth/register/passkey/options").session(session))
                .andReturn()
                .getResponse()
                .getContentAsString();
        var key = new SoftAuthenticator(ORIGIN);
        postJson("/api/auth/register/passkey", session, "{\"credential\":" + key.create(creation) + "}")
                .andExpect(status().isCreated());

        var options = mvc.perform(post("/api/auth/step-up/passkey/options").session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var body = postJson("/api/auth/step-up/passkey", session, "{\"credential\":" + key.get(options) + "}")
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var jwt = decoder.decode(JsonPath.read(body, "$.proof"));
        assertThat(jwt.getClaimAsStringList("amr")).containsExactly("hwk");
    }

    @Test
    void passkeyWithoutOptions_isRejected() throws Exception {
        var user = register(newPerson());
        postJson("/api/auth/step-up/passkey", user.session(), "{\"credential\":{}}")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("flow_not_started"));
    }
}
