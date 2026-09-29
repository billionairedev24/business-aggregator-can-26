package ca.northline.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Factor;
import ca.northline.auth.support.AuthIntegrationTest;
import com.github.f4b6a3.ulid.UlidCreator;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
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

    private static final String REDIRECT = "http://localhost:3100/login/oauth2/code/studio";
    private static final String VERIFIER = "dBjftJeZ4CVP-mB92K27uhbUJU1p1r_wW1gFWFOEjXk-northline-test";

    @Test
    void mfaSession_getsCodeSilently_andTokensCarryClaims() throws Exception {
        var user = register(newPerson());
        var merchantId = business(user.userId());
        jdbc.sql("INSERT INTO identity.platform_roles (user_id, role) VALUES (:u, 'staff')")
                .param("u", user.userId())
                .update();

        var tokens = tokens(authorize().session(user.session()));

        var access = payload(tokens.get("access_token"));
        assertThat(JsonPath.<String>read(access, "$.sub")).isEqualTo(user.userId());
        assertThat(JsonPath.<String>read(access, "$.acr")).isEqualTo("mfa");
        assertThat(JsonPath.<List<String>>read(access, "$.merchants")).containsExactly(merchantId);
        assertThat(JsonPath.<List<String>>read(access, "$.roles")).containsExactly("staff");
        assertThat(JsonPath.<List<String>>read(access, "$.aud")).contains("studio-bff", "northline-api");
        assertThat(JsonPath.<String>read(access, "$.scope")).contains("merchant");
        assertThat(JsonPath.<String>read(header(tokens.get("access_token")), "$.alg"))
                .isEqualTo("ES256");

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

        var tokens = tokens(authorize().session(new MockHttpSession()).with(authentication(singleFactor)));

        var access = payload(tokens.get("access_token"));
        assertThat(JsonPath.<Map<String, Object>>read(access, "$")).doesNotContainKey("acr");
        assertThat(JsonPath.<List<String>>read(access, "$.amr")).containsExactly("sms");
        assertThat(JsonPath.<Map<String, Object>>read(payload(tokens.get("id_token")), "$"))
                .doesNotContainKey("acr");
    }

    @Test
    void noSession_authorizeRedirectsToTheStudioSignInPage() throws Exception {
        mvc.perform(authorize().session(new MockHttpSession()))
                .andExpect(status().is3xxRedirection())
                .andExpect(redirectedUrl("http://localhost:3100/sign-in"));
    }

    private MockHttpServletRequestBuilder authorize() throws Exception {
        var challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize")
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", "studio-bff")
                .queryParam("scope", "openid profile merchant")
                .queryParam("redirect_uri", REDIRECT)
                .queryParam("state", "s1")
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    private Map<String, String> tokens(MockHttpServletRequestBuilder authorize) throws Exception {
        var location = mvc.perform(authorize)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith(REDIRECT);
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");
        assertThat(code).isNotBlank();
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
