package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/** Sign in: email or mobile → authenticator code / backup code → signed in. */
class SignInApiTest extends AuthIntegrationTest {

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private MockHttpSession startedFor(String identifier) throws Exception {
        var session = new MockHttpSession();
        postJson("/api/auth/sign-in", session, json(Map.of("identifier", identifier)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.factors", contains("passkey", "totp", "backup_code")));
        return session;
    }

    @Test
    void totpSignIn_byEmail_setsMfaSession() throws Exception {
        var user = register(newPerson());
        var session = startedFor(user.person().email());
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.id").value(user.userId()))
                .andExpect(jsonPath("$.acr").value("mfa"));
        mvc.perform(get("/api/auth/session").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.firstName").value("Amara"));
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM identity.sessions WHERE user_id = :u AND method = 'totp' AND acr = 'mfa'")
                        .param("u", user.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(1);
    }

    @Test
    void totpSignIn_byMobile_inAnyFormat() throws Exception {
        var user = register(newPerson());
        var session = startedFor(user.e164Digits());
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isOk());
    }

    @Test
    void totpCode_cannotBeReplayed() throws Exception {
        var user = register(newPerson());
        var code = totpNow(user.totpSecret());
        postJson("/api/auth/sign-in/totp", startedFor(user.person().email()), json(Map.of("code", code)))
                .andExpect(status().isOk());
        postJson("/api/auth/sign-in/totp", startedFor(user.person().email()), json(Map.of("code", code)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That code didn't work. Check it and try again."));
    }

    @Test
    void wrongCode_isLoggedAndCountedUntilLocked() throws Exception {
        var user = register(newPerson());
        var session = startedFor(user.person().email());
        for (int i = 0; i < 4; i++) {
            postJson("/api/auth/sign-in/totp", session, json(Map.of("code", "000000")))
                    .andExpect(status().isUnprocessableContent())
                    .andExpect(jsonPath("$.errors[0].field").value("code"));
        }
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", "000000")))
                .andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("too_many_attempts"));
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", totpNow(user.totpSecret()))))
                .andExpect(status().isTooManyRequests());
        assertThat(jdbc.sql(
                                "SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = 'auth.sign_in_failed'")
                        .param("u", user.userId())
                        .query(Long.class)
                        .single())
                .isEqualTo(5);
    }

    @Test
    void unknownAccount_looksTheSameUntilTheFactorFails() throws Exception {
        var session = startedFor("nobody-" + System.nanoTime() + "@example.ca");
        postJson("/api/auth/sign-in/totp", session, json(Map.of("code", "123456")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value("That code didn't work. Check it and try again."));
    }

    @Test
    void identifierIsRequired() throws Exception {
        postJson("/api/auth/sign-in", new MockHttpSession(), json(Map.of("identifier", " ")))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("identifier"))
                .andExpect(jsonPath("$.errors[0].message").value("Enter your email or mobile."));
    }

    @Test
    void backupCode_worksOnce() throws Exception {
        var user = register(newPerson());
        var codes = backupCodes(user.session());
        var code = codes.get(3).toUpperCase(java.util.Locale.ROOT).replace("-", " ");

        postJson("/api/auth/sign-in/backup-code", startedFor(user.person().email()), json(Map.of("code", code)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.acr").value("mfa"));
        postJson("/api/auth/sign-in/backup-code", startedFor(user.person().email()), json(Map.of("code", code)))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.errors[0].message").value("That backup code didn't work, or it was already used."));
        // another unused code still works
        postJson("/api/auth/sign-in/backup-code", startedFor(user.person().email()), json(Map.of("code", codes.get(4))))
                .andExpect(status().isOk());
    }

    @Test
    void regeneratingBackupCodes_invalidatesTheOldSet() throws Exception {
        var user = register(newPerson());
        var first = backupCodes(user.session());
        backupCodes(user.session());
        postJson(
                        "/api/auth/sign-in/backup-code",
                        startedFor(user.person().email()),
                        json(Map.of("code", first.getFirst())))
                .andExpect(status().isUnprocessableContent());
    }

    @Test
    void backupCodes_needASecondFactorSession() throws Exception {
        mvc.perform(post("/api/auth/backup-codes").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void signOut_endsTheAuthSession() throws Exception {
        var user = register(newPerson());
        mvc.perform(get("/api/auth/session").session(user.session())).andExpect(status().isOk());
        mvc.perform(post("/api/auth/sign-out").session(user.session())).andExpect(status().isNoContent());
        mvc.perform(get("/api/auth/session").session(user.session())).andExpect(status().isUnauthorized());
    }

    @Test
    void passkeyOptions_listTheAccountsCredentials_orNoneForDiscoverableSignIn() throws Exception {
        mvc.perform(post("/api/auth/sign-in/passkey/options").session(new MockHttpSession()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.challenge").isString())
                .andExpect(jsonPath("$.rpId").value("localhost"))
                .andExpect(jsonPath("$.allowCredentials", hasSize(0)));
    }

    private List<String> backupCodes(MockHttpSession session) throws Exception {
        var body = mvc.perform(post("/api/auth/backup-codes").session(session))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.codes", hasSize(10)))
                .andReturn()
                .getResponse()
                .getContentAsString();
        return JsonPath.read(body, "$.codes");
    }
}
