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
        return registerWithPasskey(new SoftAuthenticator(ORIGIN));
    }

    private Enrolled registerWithPasskey(SoftAuthenticator key) throws Exception {
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
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(options, "$.authenticatorSelection.userVerification"))
                .isEqualTo("required");
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
        // S-20: never the typed account's credentials — the options would tell whether it exists
        assertThat(com.jayway.jsonpath.JsonPath.<java.util.List<Object>>read(options, "$.allowCredentials"))
                .isEmpty();
        assertThat(com.jayway.jsonpath.JsonPath.<String>read(options, "$.userVerification"))
                .isEqualTo("required");
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

    // ── S-20 ─────────────────────────────────────────────────────────────────────────────────────────────────────

    private ResultActions signInWith(Enrolled enrolled, SoftAuthenticator key) throws Exception {
        var session = new MockHttpSession();
        postJson(
                "/api/auth/sign-in",
                session,
                json(Map.of("identifier", enrolled.person().email())));
        var options = options("/api/auth/sign-in/passkey/options", session);
        return postJson("/api/auth/sign-in/passkey", session, "{\"credential\":" + key.get(options) + "}");
    }

    private static final String FAILED = "That passkey couldn't be verified. Try again or use another method.";

    @Test
    void signInOptions_areTheSameForAnAccountWithAPasskey_andForNobody() throws Exception {
        var enrolled = registerWithPasskey();
        var known = new MockHttpSession();
        postJson(
                "/api/auth/sign-in",
                known,
                json(Map.of("identifier", enrolled.person().email())));
        var unknown = new MockHttpSession();
        postJson(
                "/api/auth/sign-in",
                unknown,
                json(Map.of("identifier", "nobody-" + System.nanoTime() + "@example.ca")));
        var a = options("/api/auth/sign-in/passkey/options", known);
        var b = options("/api/auth/sign-in/passkey/options", unknown);
        assertThat(a.replaceAll("\"challenge\":\"[^\"]+\"", ""))
                .isEqualTo(b.replaceAll("\"challenge\":\"[^\"]+\"", ""));
    }

    @Test
    void anAssertionWithoutUserVerification_isRejected() throws Exception {
        var enrolled = registerWithPasskey();
        enrolled.key().withoutUserVerification();
        signInWith(enrolled, enrolled.key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(FAILED));
    }

    @Test
    void aPasskeyWithoutUserVerification_cantBeRegistered() throws Exception {
        var person = newPerson();
        var session = new MockHttpSession();
        postJson("/api/auth/register", session, person.json()).andExpect(status().isOk());
        postJson("/api/auth/register/verify", session, json(Map.of("code", sms.lastCodeTo(person.e164()))))
                .andExpect(status().isOk());
        var options = options("/api/auth/register/passkey/options", session);
        var key = new SoftAuthenticator(ORIGIN).withoutUserVerification();
        postJson("/api/auth/register/passkey", session, "{\"credential\":" + key.create(options) + "}")
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].field").value("credential"));
    }

    @Test
    void aClonedAuthenticatorBehindOnItsCounter_isRejected() throws Exception {
        var enrolled = registerWithPasskey();
        signInWith(enrolled, enrolled.key()).andExpect(status().isOk()); // counter 1
        signInWith(enrolled, enrolled.key()).andExpect(status().isOk()); // counter 2
        enrolled.key().counterAt(1); // the clone presents 2 again
        signInWith(enrolled, enrolled.key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(FAILED));
        enrolled.key().counterAt(0); // and 1
        signInWith(enrolled, enrolled.key()).andExpect(status().isUnprocessableContent());
        enrolled.key().counterAt(2); // the real one moves on: 3
        signInWith(enrolled, enrolled.key()).andExpect(status().isOk());
    }

    @Test
    void anAuthenticatorThatDoesntCount_keepsWorking() throws Exception {
        var enrolled = registerWithPasskey(new SoftAuthenticator(ORIGIN).notCounting());
        signInWith(enrolled, enrolled.key()).andExpect(status().isOk());
        signInWith(enrolled, enrolled.key()).andExpect(status().isOk());
    }

    @Test
    void anAssertionMadeForAnotherOrigin_isRejected() throws Exception {
        var enrolled = registerWithPasskey();
        enrolled.key().fromOrigin("https://studio.northline.example");
        signInWith(enrolled, enrolled.key())
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.errors[0].message").value(FAILED));
    }
}
