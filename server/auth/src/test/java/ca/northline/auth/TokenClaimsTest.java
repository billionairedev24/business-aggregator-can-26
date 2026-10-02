package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.application.ErasedAccounts;
import ca.northline.auth.domain.Factor;
import ca.northline.auth.support.AuthIntegrationTest;
import com.github.f4b6a3.ulid.UlidCreator;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * The hand-off to the studio BFF: an authenticated auth session makes {@code /oauth2/authorize} issue a code silently;
 * the tokens carry {@code sub}, {@code roles}, {@code merchants}, and {@code acr=mfa} only after a second factor.
 */
class TokenClaimsTest extends AuthIntegrationTest {

    private static final Client STUDIO = new Client(
            "studio-bff",
            "dev-studio-bff",
            "http://localhost:3100/login/oauth2/code/studio",
            "openid profile merchant");
    /** S-62: single-factor sign-ins get tokens for consumer clients only (the Studio's needs a second factor). */
    private static final Client CONSUMER = new Client(
            "consumer-bff",
            "dev-consumer-bff",
            "http://localhost:8081/login/oauth2/code/northline",
            "openid profile orders");

    /** S-90: the platform console's BFF — staff only, with a second factor. */
    private static final Client CONSOLE = new Client(
            "console-bff",
            "dev-console-bff",
            "http://localhost:8083/login/oauth2/code/console",
            "openid profile console");

    private record Client(String id, String secret, String redirect, String scope) {}

    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-northline-test";

    @Test
    void mfaSession_getsCodeSilently_andTokensCarryClaims() throws Exception {
        var user = register(newPerson());
        var merchantId = business(user.userId());
        jdbc.sql("INSERT INTO identity.platform_roles (user_id, role) VALUES (:u, 'staff')")
                .param("u", user.userId())
                .update();

        var tokens = tokens(STUDIO, authorize(STUDIO).session(user.session()));

        var access = payload(tokens.get("access_token"));
        assertThat(JsonPath.<String>read(access, "$.sub")).isEqualTo(user.userId());
        assertThat(JsonPath.<String>read(access, "$.acr")).isEqualTo("mfa");
        assertThat(JsonPath.<List<String>>read(access, "$.merchants")).containsExactly(merchantId);
        assertThat(JsonPath.<List<String>>read(access, "$.roles")).containsExactly("staff");
        assertThat(JsonPath.<List<String>>read(access, "$.aud")).contains("studio-bff", "northline-api");
        assertThat(JsonPath.<String>read(access, "$.scope")).contains("merchant");
        assertThat(JsonPath.<String>read(header(tokens.get("access_token")), "$.alg"))
                .isEqualTo("ES256");
        // kid = the published key's thumbprint (S-7): resource servers pick the key from the JWK set by it.
        String kid = JsonPath.read(header(tokens.get("access_token")), "$.kid");
        var jwks = mvc.perform(get("/oauth2/jwks"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(JsonPath.<List<String>>read(jwks, "$.keys[*].kid")).contains(kid);
        assertThat(JsonPath.<List<Object>>read(jwks, "$.keys[*].d")).isEmpty();

        var id = payload(tokens.get("id_token"));
        assertThat(JsonPath.<String>read(id, "$.given_name")).isEqualTo("Amara");
        assertThat(JsonPath.<String>read(id, "$.family_name")).isEqualTo("Osei");
        assertThat(JsonPath.<String>read(id, "$.email")).isEqualTo(user.person().email());
        assertThat(JsonPath.<String>read(id, "$.acr")).isEqualTo("mfa");
        assertThat(JsonPath.<String>read(id, "$.member_since")).matches("\\d{4}-\\d{2}-\\d{2}");
        assertThat(JsonPath.<String>read(header(tokens.get("id_token")), "$.alg"))
                .isEqualTo("ES256");
    }

    @Test
    void withoutSecondFactor_tokensHaveNoAcr() throws Exception {
        var user = register(newPerson());
        var singleFactor = UsernamePasswordAuthenticationToken.authenticated(
                user.userId(),
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        FactorGrantedAuthority.withAuthority(Factor.PHONE_OTP.authority())
                                .issuedAt(clock.instant())
                                .build()));

        var tokens = tokens(
                CONSUMER, authorize(CONSUMER).session(new MockHttpSession()).with(authentication(singleFactor)));

        var access = payload(tokens.get("access_token"));
        assertThat(JsonPath.<Map<String, Object>>read(access, "$")).doesNotContainKey("acr");
        assertThat(JsonPath.<List<String>>read(access, "$.amr")).containsExactly("sms");
        assertThat(JsonPath.<Map<String, Object>>read(payload(tokens.get("id_token")), "$"))
                .doesNotContainKey("acr");
    }

    @Test
    void noSession_authorizeRedirectsToTheStudioSignInPage() throws Exception {
        mvc.perform(authorize(STUDIO).session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3100/sign-in"));
    }

    @Test
    void consoleTokens_carryTheStaffRoles_inTheIdTokenToo() throws Exception {
        var user = register(newPerson());
        jdbc.sql("""
                        INSERT INTO identity.platform_roles (user_id, role)
                        VALUES (:u, 'staff'), (:u, 'trust_safety'), (:u, 'finance')""").param("u", user.userId()).update();

        var tokens = tokens(CONSOLE, authorize(CONSOLE).session(user.session()));

        assertThat(JsonPath.<List<String>>read(payload(tokens.get("access_token")), "$.roles"))
                .containsExactly("finance", "staff", "trust_safety");
        var id = payload(tokens.get("id_token"));
        assertThat(JsonPath.<List<String>>read(id, "$.roles")).containsExactly("finance", "staff", "trust_safety");
        assertThat(JsonPath.<String>read(id, "$.acr")).isEqualTo("mfa");
        // other clients' ID tokens don't list platform roles
        var studio = tokens(STUDIO, authorize(STUDIO).session(user.session()));
        assertThat(JsonPath.<Map<String, Object>>read(payload(studio.get("id_token")), "$"))
                .doesNotContainKey("roles");
    }

    @Test
    void noSession_consoleAuthorizeRedirectsToTheConsoleSignInPage() throws Exception {
        mvc.perform(authorize(CONSOLE).session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3200/sign-in"));
    }

    @Test
    void aSingleFactorSession_getsNoConsoleCode_andIsSentToTheConsoleSignInPage() throws Exception {
        var user = register(newPerson());
        var singleFactor = UsernamePasswordAuthenticationToken.authenticated(
                user.userId(),
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        FactorGrantedAuthority.withAuthority(Factor.PHONE_OTP.authority())
                                .issuedAt(clock.instant())
                                .build()));
        mvc.perform(authorize(CONSOLE).session(new MockHttpSession()).with(authentication(singleFactor)))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3200/sign-in"));
    }

    @Autowired
    ErasedAccounts erasedAccounts;

    /** S-105: an account the privacy pipeline closed gets no token, and its auth rows are purged. */
    @Test
    void erasedAccount_getsNoTokens_andItsAuthDataIsPurged() throws Exception {
        var user = register(newPerson());
        var location = mvc.perform(authorize(STUDIO).session(user.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        var code = UriComponentsBuilder.fromUriString(Objects.requireNonNull(location))
                .build()
                .getQueryParams()
                .getFirst("code");
        jdbc.sql("UPDATE identity.users SET status = 'erased' WHERE id = :u")
                .param("u", user.userId())
                .update();

        mvc.perform(post("/oauth2/token")
                        .with(httpBasic(STUDIO.id(), STUDIO.secret()))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", STUDIO.redirect())
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_grant"));

        assertThat(erasedAccounts.purge(1000)).isPositive();
        for (var table : List.of(
                "auth.oauth2_authorization WHERE principal_name = :u",
                "auth.totp_secrets WHERE user_id = :u",
                "auth.backup_codes WHERE user_id = :u",
                "auth.user_entities WHERE name = :u")) {
            assertThat(jdbc.sql("SELECT count(*) FROM " + table)
                            .param("u", user.userId())
                            .query(Integer.class)
                            .single())
                    .as(table)
                    .isZero();
        }
        assertThat(erasedAccounts.purge(1000))
                .as("nothing left for that account")
                .isZero();
    }

    private MockHttpServletRequestBuilder authorize(Client client) throws Exception {
        var challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize")
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", client.id())
                .queryParam("scope", client.scope())
                .queryParam("redirect_uri", client.redirect())
                .queryParam("state", "s1")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    private Map<String, String> tokens(Client client, MockHttpServletRequestBuilder authorize) throws Exception {
        var location = mvc.perform(authorize)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(client.redirect());
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();
        var body = mvc.perform(post("/oauth2/token")
                        .with(httpBasic(client.id(), client.secret()))
                        .param("grant_type", "authorization_code")
                        .param("code", code)
                        .param("redirect_uri", client.redirect())
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        return Map.of(
                "access_token", JsonPath.read(body, "$.access_token"),
                "id_token", JsonPath.read(body, "$.id_token"));
    }

    private String business(String userId) {
        var merchantId = UlidCreator.getMonotonicUlid().toString();
        jdbc.sql("""
                        INSERT INTO merchants.merchants (id, type, display_name, legal_name, structure, tier, status, city)
                        VALUES (:id, 'provider', 'Prairie Wrench', 'Prairie Wrench Ltd.', 'sole', 'registered', 'active', 'Calgary')
                        """).param("id", merchantId).update();
        jdbc.sql("""
                        INSERT INTO merchants.merchant_members (merchant_id, user_id, role, bookable, mfa_ok)
                        VALUES (:m, :u, 'owner', true, true)""").param("m", merchantId).param("u", userId).update();
        return merchantId;
    }

    private static String payload(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }

    private static String header(String jwt) {
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[0]), StandardCharsets.UTF_8);
    }
}
