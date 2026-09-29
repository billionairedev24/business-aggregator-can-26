package ca.northline.auth;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SoftAuthenticator;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;

/** Studio Settings › Security: {@code /api/auth/security} (settings &amp; compliance workstream). */
class SecurityApiTest extends AuthIntegrationTest {

    @Test
    void overviewShowsFactors_backupCodesLeft_andSignIns() throws Exception {
        var user = register(newPerson());
        mvc.perform(post("/api/auth/backup-codes").session(user.session())).andExpect(status().isOk());

        mvc.perform(get("/api/auth/security").session(user.session()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.email").value(user.person().email()))
                .andExpect(jsonPath("$.mfaPrimary").value("totp"))
                .andExpect(jsonPath("$.authenticator").value(true))
                .andExpect(jsonPath("$.passkeys", hasSize(0)))
                .andExpect(jsonPath("$.backupCodesRemaining").value(10))
                .andExpect(jsonPath("$.signIns", hasSize(1)))
                .andExpect(jsonPath("$.signIns[0].method").value("registration"));
    }

    @Test
    void addsASecurityKey() throws Exception {
        var user = register(newPerson());
        var options = mvc.perform(post("/api/auth/security/passkeys/options").session(user.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var key = new SoftAuthenticator("http://localhost:3100");
        mvc.perform(post("/api/auth/security/passkeys")
                        .session(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":" + key.create(options) + ",\"label\":\"YubiKey 5C\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.passkeys", hasSize(1)))
                .andExpect(jsonPath("$.passkeys[0].label").value("YubiKey 5C"));
        mvc.perform(get("/api/auth/security").session(user.session()))
                .andExpect(jsonPath("$.passkeys[0].label").value("YubiKey 5C"));
    }

    @Test
    void needsASecondFactorSession() throws Exception {
        mvc.perform(get("/api/auth/security").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("unauthenticated"));
        mvc.perform(post("/api/auth/security/passkeys/options").session(new MockHttpSession()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void addingAKeyNeedsTheOptionsFirst() throws Exception {
        var user = register(newPerson());
        mvc.perform(post("/api/auth/security/passkeys")
                        .session(user.session())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"credential\":{}}"))
                .andExpect(status().isConflict());
    }
}
