package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.SoftAuthenticator;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.web.servlet.ResultActions;

/** Passkeys end to end through the JSON API, with a software authenticator standing in for the browser. */
class PasskeyApiTest extends AuthIntegrationTest {

    private static final String ORIGIN = "http://localhost:3100";

    private ResultActions postJson(String path, MockHttpSession session, String body) throws Exception {
        return mvc.perform(post(path)
                .session(session)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body));
    }

    private String options(String path, MockHttpSession session) throws Exception {
        return mvc.perform(post(path).session(session))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private record Enrolled(Person person, SoftAuthenticator key) {}

    private Enrolled registerWithPasskey() throws Exception {
        var person = newPerson();
        var session = new MockHttpSession();
        postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                .andExpect(status().isOk());
        var options = options("/api/auth/register/passkey/options", session);
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(options, "$.user.name"))
                .isEqualTo(person.email());
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(options, "$.user.displayName"))
                .isEqualTo("Amara Osei");
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(options, "$.authenticatorSelection.residentKey"))
                .isEqualTo("required");
        var key = new SoftAuthenticator(ORIGIN);
        postJson("/api/auth/register/passkey", session, "{\"credential\":" + key.create(options) + "}")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.user.firstName").value("Amara"))
                .andExpect(jsonPath("$.acr").value("mfa"));
        return new Enrolled(person, key);
    }

    @Test
    void registerWithPasskey_thenSignInWithIt() throws Exception {
        var enrolled = registerWithPasskey();
        assertThat(jdbc.sql("SELECT mfa_primary FROM identity.users WHERE email = CAST(:e AS citext)")
                        .param("e", enrolled.person().email())
                        .query(String.class)
                        .single())
                .isEqualTo("passkey");

        var session = new MockHttpSession();
        postJson(
                        "/api/auth/sign-in",
                        session,
                        json(Map.of("identifier", enrolled.person().email())))
                .andExpect(status().isOk());
        var options = options("/api/auth/sign-in/passkey/options", session);
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(options, "$.allowCredentials"))
                .hasSize(1);
        postJson(
                        "/api/auth/sign-in/passkey",
                        session,
                        "{\"credential\":" + enrolled.key().get(options) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(enrolled.person().email()))
                .andExpect(jsonPath("$.acr").value("mfa"));
    }

    @Test
    void discoverablePasskey_signsInWithoutTypingAnEmail() throws Exception {
        var enrolled = registerWithPasskey();
        var session = new MockHttpSession();
        var options = options("/api/auth/sign-in/passkey/options", session);
        postJson(
                        "/api/auth/sign-in/passkey",
                        session,
                        "{\"credential\":" + enrolled.key().get(options) + "}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.user.email").value(enrolled.person().email()));
    }

    @Test
    void badSignature_isRejected() throws Exception {
        var enrolled = registerWithPasskey();
        var session = new MockHttpSession();
        postJson(
                "/api/auth/sign-in",
                session,
                json(Map.of("identifier", enrolled.person().email())));
        var options = options("/api/auth/sign-in/passkey/options", session);
        postJson(
                        "/api/auth/sign-in/passkey",
                        session,
                        "{\"credential\":" + enrolled.key().get(options, true) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field").value("credential"))
                .andExpect(jsonPath("$.errors[0].message")
                        .value("That passkey couldn't be verified. Try again or use another method."));
    }

    @Test
    void anotherAccountsPasskey_doesNotSignInTheTypedAccount() throws Exception {
        var mine = registerWithPasskey();
        var someoneElse = register(newPerson());
        var session = new MockHttpSession();
        postJson(
                "/api/auth/sign-in",
                session,
                json(Map.of("identifier", someoneElse.person().email())));
        var options = options("/api/auth/sign-in/passkey/options", session);
        postJson(
                        "/api/auth/sign-in/passkey",
                        session,
                        "{\"credential\":" + mine.key().get(options) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("credential"));
    }
}
