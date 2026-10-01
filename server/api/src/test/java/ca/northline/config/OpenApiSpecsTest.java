package ca.northline.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.openapi.OpenApiSnapshot;
import ca.northline.support.IntegrationTest;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

/**
 * S-125: the api's OpenAPI documents — committed specs equal the code's (drift check; {@code make openapi} rewrites
 * them), every /api path is in some audience's document, the partner document is exactly the {@code @PartnerAccess}
 * handlers, the three viewers and the landing page render under the docs CSP, and production publishes nothing.
 */
class OpenApiSpecsTest extends IntegrationTest {

    static final List<String> GROUPS = List.of("public", "studio", "partner", "console", "webhooks", "internal");

    @Autowired
    @Qualifier("requestMappingHandlerMapping")
    RequestMappingHandlerMapping mappings;

    @Test
    void committedSpecsMatchTheCode() throws Exception {
        OpenApiSnapshot.verify(mvc, "/v3/api-docs", "api", GROUPS);
    }

    @Test
    void everyApiPathIsInAnAudienceDocument() throws Exception {
        Set<String> documented = new HashSet<>();
        for (var group : GROUPS) {
            Map<String, Object> paths = JsonPath.read(json("/v3/api-docs/" + group), "$.paths");
            if (paths != null) {
                documented.addAll(paths.keySet());
            }
        }
        var served = new TreeSet<String>();
        mappings.getHandlerMethods()
                .keySet()
                .forEach(info -> info.getPatternValues().stream()
                        .filter(p -> p.startsWith("/api/"))
                        .forEach(served::add));
        assertThat(served).isNotEmpty();
        assertThat(served).as("api paths in no OpenApiConfig group").allMatch(documented::contains);
    }

    @Test
    void documentsAreOpenApi31WithTheSharedConventions() throws Exception {
        var studio = json("/v3/api-docs/studio");
        assertThat(JsonPath.<String>read(studio, "$.openapi")).startsWith("3.1");
        assertThat(JsonPath.<String>read(studio, "$.servers[0].url")).isEqualTo("http://localhost:8080");
        assertThat(JsonPath.<Map<String, Object>>read(studio, "$.components.securitySchemes"))
                .containsOnlyKeys("bffSession", "csrf", "oauth2"); // only what the document uses
        assertThat(JsonPath.<String>read(json("/v3/api-docs/public"), "$.components.securitySchemes.dpop.scheme"))
                .isEqualTo("DPoP");
        assertThat(JsonPath.<String>read(
                        studio, "$.components.securitySchemes.oauth2.flows.authorizationCode.tokenUrl"))
                .isEqualTo("http://localhost:9000/oauth2/token");
        assertThat(JsonPath.<List<String>>read(
                        json("/v3/api-docs/partner"),
                        "$.components.securitySchemes.partnerClientCredentials.x-token-endpoint-auth-methods"))
                .containsExactly("private_key_jwt");
        assertThat(JsonPath.<String>read(studio, "$.components.schemas.Ulid.pattern"))
                .isEqualTo("^[0-9A-HJKMNP-TV-Z]{26}$");
        assertThat(JsonPath.<List<String>>read(studio, "$.components.schemas.ValidationErrors.required"))
                .containsExactly("errors");
        // A merchant-scoped write: ULID path parameter, 422 with the validation format, 403 problem details.
        var patch = "$.paths['/api/v1/merchants/{merchantId}'].patch";
        assertThat(JsonPath.<Object>read(studio, patch + ".parameters[?(@.name=='merchantId')].schema.$ref")
                        .toString())
                .contains("Ulid");
        assertThat(JsonPath.<String>read(studio, patch + ".responses['422'].content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/ValidationErrors");
        assertThat(JsonPath.<String>read(
                        studio, patch + ".responses['403'].content['application/problem+json'].schema.$ref"))
                .isEqualTo("#/components/schemas/Problem");
    }

    @Test
    void publicReadsNeedNoSignInButTheCustomerEndpointsDo() throws Exception {
        var pub = json("/v3/api-docs/public");
        assertThat(JsonPath.<List<Object>>read(pub, "$.paths['/api/v1/storefronts/{slug}'].get.security"))
                .isEmpty();
        assertThat(JsonPath.<Map<String, Object>>read(pub, "$.paths['/api/v1/me'].get"))
                .doesNotContainKey("security");
        assertThat(JsonPath.<List<Map<String, Object>>>read(pub, "$.security"))
                .extracting(r -> r.keySet())
                .anySatisfy(keys -> assertThat(keys).contains("dpop", "dpopProof"));
    }

    @Test
    void partnerDocumentIsExactlyThePartnerAccessHandlers() throws Exception {
        var partner = json("/v3/api-docs/partner");
        Map<String, Object> paths = JsonPath.read(partner, "$.paths");
        assertThat(paths)
                .containsOnlyKeys(
                        "/api/v1/merchants/{merchantId}/listings",
                        "/api/v1/merchants/{merchantId}/listings/{listingId}");
        assertThat(JsonPath.<List<String>>read(
                        partner,
                        "$.paths['/api/v1/merchants/{merchantId}/listings'].get.security[0].partnerClientCredentials"))
                .containsExactly("api.read");
    }

    @Test
    void webhooksDocumentHasEveryPayloadSchema() throws Exception {
        var webhooks = json("/v3/api-docs/webhooks");
        Map<String, Object> hooks = JsonPath.read(webhooks, "$.webhooks");
        assertThat(hooks).containsKeys("booking.completed", "payment.released", "refund.issued", "webhook.test");
        assertThat(JsonPath.<String>read(
                        webhooks,
                        "$.webhooks['booking.completed'].post.requestBody.content['application/json'].schema.$ref"))
                .isEqualTo("#/components/schemas/BookingCompletedWebhookV1");
        assertThat(JsonPath.<List<String>>read(webhooks, "$.webhooks['booking.completed'].post.parameters[*].name"))
                .contains("Northline-Signature", "Northline-Event-Id");
    }

    @Test
    void allThreeViewersAndTheLandingPageRender() throws Exception {
        var csp = "script-src 'self' 'unsafe-inline'";
        var landing = mvc.perform(get("/docs"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Security-Policy", org.hamcrest.Matchers.containsString(csp)))
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        for (var group : GROUPS) {
            assertThat(landing)
                    .contains("/swagger-ui.html?urls.primaryName=" + group)
                    .contains("/docs/redoc?group=" + group);
        }
        mvc.perform(get("/docs/redoc"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/docs/redoc/redoc.standalone.js")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/v3/api-docs/partner")));
        assertThat(body("/docs/redoc/redoc.standalone.js")).contains("Redoc");
        assertThat(body("/docs/assets/redoc-init.js")).contains("Redoc.init");
        assertThat(body("/swagger-ui/index.html")).contains("swagger-ui");
        mvc.perform(get("/v3/api-docs/swagger-config"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("/v3/api-docs/internal")));
        // Scalar: one page, every group as a source (springdoc's Scalar starter), its bundle served by the app
        var scalar = mvc.perform(get("/docs/scalar"))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
        for (var group : GROUPS) {
            assertThat(scalar).contains("/v3/api-docs/" + group);
        }
        assertThat(scalar).doesNotContain("cdn.jsdelivr.net");
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

    private String json(String path) throws Exception {
        return mvc.perform(get(path))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString(StandardCharsets.UTF_8);
    }
}
