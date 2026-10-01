package ca.northline.auth.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.support.AuthIntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.bootstrap.DefaultBootstrapContext;
import org.springframework.boot.context.config.ConfigDataEnvironmentPostProcessor;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.http.MediaType;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-139: the public client {@code docs} lets the api's Swagger UI and Scalar sign in ("Authorize", then "Try it") with
 * the person's own session: authorization code + PKCE from the browser, the token exchanged cross-origin from the
 * viewers' page (CORS for that origin only), no refresh token. Registered under local, test, dev and staging, never
 * under prod.
 */
class DocsClientTest extends AuthIntegrationTest {

    /** {@code API_PUBLIC_URL}'s local default: where the api serves the viewers. */
    static final String VIEWERS = "http://localhost:8080";

    static final String SWAGGER_REDIRECT = VIEWERS + "/swagger-ui/oauth2-redirect.html";
    static final String VERIFIER = "docs-test-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";

    @Test
    void swaggerUi_signsInWithTheSession_andExchangesTheCodeFromItsPage() throws Exception {
        var user = register(newPerson());

        var location = mvc.perform(get("/oauth2/authorize")
                        .session(user.session())
                        .accept(MediaType.TEXT_HTML)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "docs")
                        .queryParam("scope", "openid profile merchant")
                        .queryParam("redirect_uri", SWAGGER_REDIRECT)
                        .queryParam("state", "swagger-state")
                        .queryParam("code_challenge", challenge())
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).as("no consent page: the person's own session").startsWith(SWAGGER_REDIRECT + "?code=");
        var code = UriComponentsBuilder.fromUriString(location)
                .build()
                .getQueryParams()
                .getFirst("code");

        // Swagger UI's request (X-Requested-With) needs a preflight from the viewers' origin
        mvc.perform(options("/oauth2/token")
                        .header("Origin", VIEWERS)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "content-type,x-requested-with"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", VIEWERS));
        var body = mvc.perform(post("/oauth2/token")
                        .header("Origin", VIEWERS)
                        .header("X-Requested-With", "XMLHttpRequest")
                        .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                        .param("grant_type", "authorization_code")
                        .param("client_id", "docs")
                        .param("code", code)
                        .param("redirect_uri", SWAGGER_REDIRECT)
                        .param("code_verifier", VERIFIER))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", VIEWERS))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jwt = JsonPath.read(body, "$.access_token");
        var claims = new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
        assertThat(JsonPath.<List<String>>read(claims, "$.aud")).contains("northline-api");
        assertThat(JsonPath.<String>read(claims, "$.client_id")).isEqualTo("docs");
        assertThat(JsonPath.<String>read(claims, "$.sub")).isEqualTo(user.userId());
        assertThat(JsonPath.<String>read(claims, "$.scope")).isEqualTo("openid profile merchant");
    }

    @Test
    void anotherOrigin_getsNoCors_andAnotherRedirectIsRefused() throws Exception {
        mvc.perform(options("/oauth2/token")
                        .header("Origin", "https://attacker.example")
                        .header("Access-Control-Request-Method", "POST"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
        var user = register(newPerson());
        mvc.perform(get("/oauth2/authorize")
                        .session(user.session())
                        .accept(MediaType.TEXT_HTML)
                        .queryParam("response_type", "code")
                        .queryParam("client_id", "docs")
                        .queryParam("scope", "openid")
                        .queryParam("redirect_uri", "https://attacker.example/swagger-ui/oauth2-redirect.html")
                        .queryParam("code_challenge", challenge())
                        .queryParam("code_challenge_method", "S256"))
                .andExpect(status().isBadRequest());
    }

    /** The client is in a configuration document for local, test, dev and staging; prod never sees it. */
    @ParameterizedTest
    @ValueSource(strings = {"dev", "staging", "prod"})
    void registeredOutsideProductionOnly(String profile) {
        var env = new StandardEnvironment();
        ConfigDataEnvironmentPostProcessor.applyTo(
                env, new DefaultResourceLoader(), new DefaultBootstrapContext(), profile, "cloud");
        var registered = env.containsProperty("northline.oauth.clients.docs.type");
        assertThat(registered).isEqualTo(!profile.equals("prod"));
        assertThat(env.containsProperty("northline.auth.token-endpoint-origins[0]"))
                .isEqualTo(!profile.equals("prod"));
        if (registered) {
            assertThat(env.getProperty("northline.oauth.clients.docs.grant-types[0]"))
                    .isEqualTo("authorization_code");
            assertThat(env.containsProperty("northline.oauth.clients.docs.grant-types[1]"))
                    .as("no refresh token for a public client without DPoP")
                    .isFalse();
        }
    }

    private static String challenge() throws Exception {
        return Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
    }
}
