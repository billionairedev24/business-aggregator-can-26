package ca.northline.auth.docs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;

import ca.northline.auth.support.AuthIntegrationTest;
import ca.northline.openapi.OpenApiSnapshot;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;

/**
 * S-125: northline-auth's OpenAPI documents equal the committed ones, describe the OAuth endpoints and the JSON API,
 * the viewers render under their own CSP while the rest of auth keeps S-20's {@code default-src 'none'}, and
 * production publishes nothing.
 */
class OpenApiSpecsTest extends AuthIntegrationTest {

    @Test
    void committedSpecsMatchTheCode() throws Exception {
        OpenApiSnapshot.verify(mvc, "/v3/api-docs", "auth", List.of("public", "internal"));
    }

    @Test
    void publicDocumentDescribesTheOAuthEndpoints() throws Exception {
        var doc = body("/v3/api-docs/public");
        Map<String, Object> paths = JsonPath.read(doc, "$.paths");
        assertThat(paths)
                .containsKeys(
                        "/.well-known/openid-configuration",
                        "/oauth2/jwks",
                        "/oauth2/authorize",
                        "/oauth2/token",
                        "/oauth2/revoke",
                        "/userinfo");
        assertThat(JsonPath.<List<String>>read(
                        doc,
                        "$.paths['/oauth2/token'].post.requestBody.content"
                                + "['application/x-www-form-urlencoded'].schema.properties.client_assertion_type.enum"))
                .containsExactly("urn:ietf:params:oauth:client-assertion-type:jwt-bearer");
        assertThat(JsonPath.<String>read(
                        doc,
                        "$.components.securitySchemes.partnerClientCredentials" + ".flows.clientCredentials.tokenUrl"))
                .isEqualTo("http://localhost:9000/oauth2/token");
    }

    @Test
    void internalDocumentIsTheJsonSignInApi() throws Exception {
        var doc = body("/v3/api-docs/internal");
        Map<String, Object> paths = JsonPath.read(doc, "$.paths");
        assertThat(paths.keySet()).isNotEmpty().allMatch(p -> p.startsWith("/api/auth/"));
        assertThat(JsonPath.<List<Object>>read(doc, "$.paths['/api/auth/sign-in'].post.security"))
                .isEmpty();
        assertThat(JsonPath.<String>read(doc, "$.components.securitySchemes.authSession.name"))
                .isEqualTo("NL_AUTH");
    }

    @Test
    void viewersHaveTheirCspAndTheRestKeepsDefaultSrcNone() throws Exception {
        var landing = mvc.perform(get("/docs")).andReturn().getResponse();
        assertThat(landing.getStatus()).isEqualTo(200);
        assertThat(landing.getHeader("Content-Security-Policy"))
                .contains("script-src 'self'")
                .contains("connect-src 'self' http://localhost:9000")
                .contains("frame-ancestors 'none'");
        assertThat(landing.getContentAsString(StandardCharsets.UTF_8))
                .contains("?urls.primaryName=public")
                .contains("/docs/redoc?group=internal");
        assertThat(body("/docs/redoc")).contains("/v3/api-docs/public");
        assertThat(body("/docs/redoc/redoc.standalone.js")).contains("Redoc");
        assertThat(body("/docs/scalar")).contains("/v3/api-docs/internal");
        assertThat(body("/swagger-ui/index.html")).contains("swagger-ui");
        var other = mvc.perform(get("/api/auth/session")).andReturn().getResponse();
        assertThat(other.getHeader("Content-Security-Policy")).startsWith("default-src 'none'");
    }

    @Test
    void productionPublishesNoSpecAndNoViewer() throws Exception {
        var prod = new YamlPropertySourceLoader()
                .load("prod", new ClassPathResource("application-prod.yml"))
                .getFirst();
        assertThat(prod.getProperty("springdoc.api-docs.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("springdoc.swagger-ui.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("scalar.enabled")).isEqualTo(false);
        assertThat(prod.getProperty("northline.docs.enabled")).isEqualTo(false);
    }

    private String body(String path) throws Exception {
        var response = mvc.perform(get(path)).andReturn().getResponse();
        assertThat(response.getStatus()).as(path).isEqualTo(200);
        return response.getContentAsString(StandardCharsets.UTF_8);
    }
}
