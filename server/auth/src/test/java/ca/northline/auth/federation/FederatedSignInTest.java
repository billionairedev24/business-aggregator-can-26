package ca.northline.auth.federation;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Totp;
import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.auth.support.FakeOidcProvider;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.jayway.jsonpath.JsonPath;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-18 against WireMock stand-ins of Google and Apple (authorization redirect, token endpoint with a signed ID token,
 * JWK set, Google's userinfo): the three outcomes (linked account, verified email of an existing account → linked only
 * after its second factor, new person → pre-filled registration → linked to the new account), Apple's form POST without
 * the session cookie, its client secret JWT, private relay addresses, unverified emails and the error mapping.
 */
class FederatedSignInTest extends AuthIntegrationTest {

    static final WireMockServer PROVIDERS = new WireMockServer(wireMockConfig().dynamicPort());
    static final FakeOidcProvider GOOGLE;
    static final FakeOidcProvider APPLE;
    static final KeyPair APPLE_KEY;
    static final String GOOGLE_CLIENT = "test-google-client.apps.example";
    static final String APPLE_CLIENT = "ca.northline.test.signin";

    static {
        PROVIDERS.start();
        try {
            GOOGLE = new FakeOidcProvider(PROVIDERS, "google");
            APPLE = new FakeOidcProvider(PROVIDERS, "apple");
            var ec = KeyPairGenerator.getInstance("EC");
            ec.initialize(new ECGenParameterSpec("secp256r1"));
            APPLE_KEY = ec.generateKeyPair();
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    @DynamicPropertySource
    static void providers(DynamicPropertyRegistry registry) {
        var pem = "-----BEGIN PRIVATE KEY-----\n"
                + Base64.getMimeEncoder().encodeToString(APPLE_KEY.getPrivate().getEncoded())
                + "\n-----END PRIVATE KEY-----";
        registry.add("northline.auth.federation.google.client-id", () -> GOOGLE_CLIENT);
        registry.add("northline.auth.federation.google.client-secret", () -> "test-google-secret-not-real");
        registry.add("northline.auth.federation.google.authorization-uri", () -> GOOGLE.url("/authorize"));
        registry.add("northline.auth.federation.google.token-uri", () -> GOOGLE.url("/token"));
        registry.add("northline.auth.federation.google.jwk-set-uri", () -> GOOGLE.url("/keys"));
        registry.add("northline.auth.federation.google.user-info-uri", () -> GOOGLE.url("/userinfo"));
        registry.add("northline.auth.federation.google.issuers", GOOGLE::issuer);
        registry.add("northline.auth.federation.apple.client-id", () -> APPLE_CLIENT);
        registry.add("northline.auth.federation.apple.team-id", () -> "TESTTEAM01");
        registry.add("northline.auth.federation.apple.key-id", () -> "TESTKEY001");
        registry.add("northline.auth.federation.apple.private-key", () -> pem);
        registry.add("northline.auth.federation.apple.authorization-uri", () -> APPLE.url("/authorize"));
        registry.add("northline.auth.federation.apple.token-uri", () -> APPLE.url("/token"));
        registry.add("northline.auth.federation.apple.jwk-set-uri", () -> APPLE.url("/keys"));
        registry.add("northline.auth.federation.apple.issuer", APPLE::issuer);
    }

    @AfterAll
    static void stop() {
        PROVIDERS.stop();
    }

    @BeforeEach
    void keys() {
        PROVIDERS.resetAll();
        GOOGLE.publishKeys();
        APPLE.publishKeys();
    }

    /** "Continue with …": the redirect to the provider; returns its query parameters. */
    private Map<String, String> startAt(String provider, MockHttpSession session) throws Exception {
        var location = mvc.perform(get("/oauth2/authorization/" + provider).session(session))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(PROVIDERS.baseUrl() + "/" + provider + "/authorize");
        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        return Map.of(
                "state", URLDecoder.decode(params.getFirst("state"), StandardCharsets.UTF_8),
                "nonce", URLDecoder.decode(params.getFirst("nonce"), StandardCharsets.UTF_8),
                "redirect_uri", URLDecoder.decode(params.getFirst("redirect_uri"), StandardCharsets.UTF_8),
                "response_mode", String.valueOf(params.getFirst("response_mode")),
                "scope", URLDecoder.decode(params.getFirst("scope"), StandardCharsets.UTF_8));
    }

    /** Google signs the person in and comes back to the callback (a GET: the session cookie is sent). */
    private String google(MockHttpSession session, Map<String, Object> claims) throws Exception {
        var request = startAt("google", session);
        GOOGLE.willIssue(GOOGLE_CLIENT, request.get("nonce"), claims);
        return mvc.perform(get("/login/oauth2/code/google")
                        .session(session)
                        .queryParam("code", "google-code")
                        .queryParam("state", request.get("state")))
                .andExpect(status().isFound())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
    }

    private static Map<String, Object> googleUser(String sub, String email, boolean verified) {
        return Map.of(
                "sub", sub,
                "email", email,
                "email_verified", verified,
                "given_name", "Amara",
                "family_name", "Osei");
    }

    private static Map<String, List<String>> query(String url) {
        return UriComponentsBuilder.fromUriString(url).build().getQueryParams().entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(
                        Map.Entry::getKey,
                        e -> e.getValue().stream()
                                .map(v -> URLDecoder.decode(v, StandardCharsets.UTF_8))
                                .toList()));
    }

    private String linkedUser(String provider, String sub) {
        return jdbc.sql("SELECT user_id FROM auth.federated_identities WHERE provider = :p AND subject = :s")
                .param("p", provider)
                .param("s", sub)
                .query(String.class)
                .optional()
                .orElse("");
    }

    private void signInWithTotp(MockHttpSession session, Registered user) throws Exception {
        clock.advanceSeconds(Totp.PERIOD_SECONDS);
        mvc.perform(post("/api/auth/sign-in/totp")
                        .session(session)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json(Map.of("code", totpNow(user.totpSecret())))))
                .andExpect(status().isOk());
    }

    @Nested
    class Google {

        @Test
        void aNewPerson_getsCreateAccountPrefilled_andTheNewAccountIsLinked() throws Exception {
            var person = newPerson();
            var sub = "g-" + UUID.randomUUID();
            var session = new MockHttpSession();
            var target = google(session, googleUser(sub, person.email(), true));

            assertThat(target).startsWith("http://localhost:3100/register?");
            var q = query(target);
            assertThat(q.get("firstName")).containsExactly("Amara");
            assertThat(q.get("lastName")).containsExactly("Osei");
            assertThat(q.get("email")).containsExactly(person.email());
            assertThat(q.get("provider")).containsExactly("google");
            assertThat(linkedUser("google", sub)).isEmpty();

            // Phone code and second factor as for every registration, in the same browser.
            var registered = register(person, session);
            assertThat(linkedUser("google", sub)).isEqualTo(registered.userId());
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM developer.audit_log WHERE actor_id = :u AND action = 'auth.federated_linked'")
                            .param("u", registered.userId())
                            .query(Long.class)
                            .single())
                    .isEqualTo(1);
        }

        @Test
        void aVerifiedEmailOfAnExistingAccount_isLinkedOnlyAfterItsSecondFactor() throws Exception {
            var user = register(newPerson());
            var sub = "g-" + UUID.randomUUID();
            var session = new MockHttpSession();
            var target = google(session, googleUser(sub, user.person().email(), true));

            assertThat(target).startsWith("http://localhost:3100/sign-in?");
            assertThat(query(target))
                    .containsEntry("step", List.of("factor"))
                    .containsEntry("identifier", List.of(user.person().email()))
                    .containsEntry("link", List.of("google"));
            assertThat(linkedUser("google", sub)).isEmpty(); // a verified email alone never links

            signInWithTotp(session, user); // the factor step: the attempt was started by the federated sign-in
            assertThat(linkedUser("google", sub)).isEqualTo(user.userId());

            // Next time: straight to the factor step of the linked account, even if the Google email changed.
            var again = google(new MockHttpSession(), googleUser(sub, "someone.else@example.ca", true));
            assertThat(query(again))
                    .containsEntry("identifier", List.of(user.person().email()))
                    .doesNotContainKey("link");
        }

        @Test
        void anotherPersonSigningIn_dropsThePendingLink() throws Exception {
            var owner = register(newPerson());
            var other = register(newPerson());
            var sub = "g-" + UUID.randomUUID();
            var session = new MockHttpSession();
            google(session, googleUser(sub, owner.person().email(), true));

            mvc.perform(post("/api/auth/sign-in")
                            .session(session)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(json(Map.of("identifier", other.person().email()))))
                    .andExpect(status().isOk());
            signInWithTotp(session, other);
            assertThat(linkedUser("google", sub)).isEmpty();
        }

        @Test
        void anUnverifiedEmail_neverMatchesAnAccount() throws Exception {
            var user = register(newPerson());
            var target = google(
                    new MockHttpSession(),
                    googleUser("g-" + UUID.randomUUID(), user.person().email(), false));
            assertThat(target).startsWith("http://localhost:3100/register?");
            assertThat(query(target)).containsEntry("email", List.of(""));
        }

        @Test
        void cancelling_atGoogle_saysSo() throws Exception {
            var session = new MockHttpSession();
            var request = startAt("google", session);
            assertThat(request.get("scope")).contains("openid", "email", "profile");
            mvc.perform(get("/login/oauth2/code/google")
                            .session(session)
                            .queryParam("error", "access_denied")
                            .queryParam("state", request.get("state")))
                    .andExpect(status().isFound())
                    .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl())
                            .isEqualTo("http://localhost:3100/sign-in?error=federation_cancelled"));
        }

        @Test
        void aTokenSignedByAnotherKey_isRefused() throws Exception {
            var session = new MockHttpSession();
            var request = startAt("google", session);
            // Same issuer and key id, another key: the signature can't verify against Google's JWK set.
            var rogue = new FakeOidcProvider(PROVIDERS, "google");
            rogue.willIssue(GOOGLE_CLIENT, request.get("nonce"), googleUser("g-x", "x@example.ca", true));
            mvc.perform(get("/login/oauth2/code/google")
                            .session(session)
                            .queryParam("code", "c")
                            .queryParam("state", request.get("state")))
                    .andExpect(status().isFound())
                    .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl())
                            .isEqualTo("http://localhost:3100/sign-in?error=federation"));
        }
    }

    @Nested
    class Apple {

        @Test
        void formPostWithoutTheSessionCookie_privateRelay_andTheGeneratedClientSecret() throws Exception {
            var request = startAt("apple", new MockHttpSession());
            assertThat(request.get("response_mode")).isEqualTo("form_post");
            assertThat(request.get("redirect_uri")).isEqualTo("http://localhost/login/oauth2/code/apple");
            var sub = "000123." + UUID.randomUUID().toString().replace("-", "");
            var relay = "x" + sub.substring(7, 17) + "@privaterelay.appleid.com";
            APPLE.willIssue(
                    APPLE_CLIENT,
                    request.get("nonce"),
                    Map.of("sub", sub, "email", relay, "email_verified", "true", "is_private_email", "true"));

            // Apple's cross-site form POST: the SameSite=Lax auth cookie isn't sent → a brand-new session.
            var target = mvc.perform(post("/login/oauth2/code/apple")
                            .session(new MockHttpSession())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("code", "apple-code")
                            .param("state", request.get("state"))
                            .param("user", """
                                    {"name":{"firstName":"Élodie","lastName":"Tremblay"},"email":"%s"}""".formatted(relay)))
                    .andExpect(status().isFound())
                    .andReturn()
                    .getResponse()
                    .getRedirectedUrl();

            assertThat(query(target))
                    .containsEntry("firstName", List.of("Élodie"))
                    .containsEntry("lastName", List.of("Tremblay"))
                    .containsEntry("email", List.of(relay))
                    .containsEntry("provider", List.of("apple"))
                    .containsEntry("relay", List.of("1"));

            var tokenRequest = PROVIDERS
                    .findAll(postRequestedFor(urlEqualTo("/apple/token")))
                    .getFirst();
            var form = query("http://x/?" + tokenRequest.getBodyAsString());
            assertThat(form.get("client_id")).containsExactly(APPLE_CLIENT);
            var secret = form.get("client_secret").getFirst();
            var parts = secret.split("\\.");
            var header = new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8);
            var claims = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            assertThat(JsonPath.<String>read(header, "$.alg")).isEqualTo("ES256");
            assertThat(JsonPath.<String>read(header, "$.kid")).isEqualTo("TESTKEY001");
            assertThat(JsonPath.<String>read(claims, "$.iss")).isEqualTo("TESTTEAM01");
            assertThat(JsonPath.<String>read(claims, "$.sub")).isEqualTo(APPLE_CLIENT);
            assertThat(JsonPath.<String>read(claims, "$.aud")).isEqualTo("https://appleid.apple.com");
            var verifier = Signature.getInstance("SHA256withECDSAinP1363Format");
            verifier.initVerify(APPLE_KEY.getPublic());
            verifier.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertThat(verifier.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
        }

        @Test
        void anUnknownState_isRefused() throws Exception {
            mvc.perform(post("/login/oauth2/code/apple")
                            .session(new MockHttpSession())
                            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                            .param("code", "apple-code")
                            .param("state", "forged-state"))
                    .andExpect(status().isFound())
                    .andExpect(r -> assertThat(r.getResponse().getRedirectedUrl())
                            .isEqualTo("http://localhost:3100/sign-in?error=federation"));
        }
    }
}
