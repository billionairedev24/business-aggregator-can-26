package ca.northline.auth.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import ca.northline.auth.domain.Factor;
import ca.northline.auth.support.AuthIntegrationTest;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.jayway.jsonpath.JsonPath;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.List;
import java.util.regex.Pattern;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpSession;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.FactorGrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * S-127: what an MCP client (Claude, an IDE, the MCP Inspector) does against the authorization server — discovery,
 * authorization code + PKCE + resource indicator with the person's consent, the token addressed to the MCP server;
 * the same through a Client ID Metadata Document instead of a registration; an unknown resource and a sign-in without
 * second factor refused.
 */
class McpAuthorizationTest extends AuthIntegrationTest {

    private static final String RESOURCE = "http://localhost:8080/mcp";
    private static final String REDIRECT = "http://127.0.0.1:43123/callback";
    private static final String VERIFIER = "mcp-test-verifier-0123456789-abcdefghijklmnopqrstuvwxyz";

    private static final WireMockServer DOCUMENTS = new WireMockServer(options().dynamicPort());

    static {
        DOCUMENTS.start();
    }

    @DynamicPropertySource
    static void documents(DynamicPropertyRegistry registry) {
        // http:// and a loopback document host are refused outside tests (see ClientIdMetadataDocumentsTest).
        registry.add("northline.auth.mcp.metadata-documents.allow-insecure", () -> "true");
    }

    @AfterAll
    static void stop() {
        DOCUMENTS.stop();
    }

    @Test
    void metadata_advertisesPkceAndClientIdMetadataDocuments() throws Exception {
        mvc.perform(get("/.well-known/oauth-authorization-server"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code_challenge_methods_supported[0]").value("S256"))
                .andExpect(jsonPath("$.client_id_metadata_document_supported").value(true))
                .andExpect(jsonPath("$.authorization_endpoint").exists())
                .andExpect(jsonPath("$.registration_endpoint").doesNotExist());
    }

    @Test
    void registeredClient_consent_tokenAddressedToTheMcpServer() throws Exception {
        var user = register(newPerson());

        var consent = mvc.perform(authorize("northline-mcp", "openid merchant mcp mcp.write", RESOURCE)
                        .session(user.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        assertThat(consent).contains("mcp.write");

        var code = consentTo("northline-mcp", consent, user.session(), List.of("merchant", "mcp", "mcp.write"));
        var access = token("northline-mcp", code, RESOURCE);

        assertThat(JsonPath.<List<String>>read(access, "$.aud")).contains(RESOURCE, "northline-api");
        assertThat(JsonPath.<String>read(access, "$.acr")).isEqualTo("mfa");
        assertThat(JsonPath.<String>read(access, "$.scope")).contains("mcp.write");

        // Consent is remembered: the next authorization for the same scopes needs no page.
        var again = mvc.perform(authorize("northline-mcp", "merchant mcp mcp.write", RESOURCE)
                        .session(user.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(again).startsWith("http://127.0.0.1:43123/callback?code=");
    }

    @Test
    void readOnlyConsent_tokenWithoutWriteScope() throws Exception {
        var user = register(newPerson());
        var consent = mvc.perform(authorize("northline-mcp", "merchant mcp mcp.write", RESOURCE)
                        .session(user.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();

        var code = consentTo("northline-mcp", consent, user.session(), List.of("merchant", "mcp"));
        var access = token("northline-mcp", code, RESOURCE);

        assertThat(JsonPath.<String>read(access, "$.scope")).contains("mcp").doesNotContain("mcp.write");
    }

    @Test
    void unknownResource_isInvalidTarget() throws Exception {
        var user = register(newPerson());
        var location = mvc.perform(authorize("northline-mcp", "merchant mcp", "https://attacker.example/mcp")
                        .session(user.session()))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith("http://127.0.0.1:43123/callback").contains("error=invalid_target");
    }

    @Test
    void tokenRequestCannotWidenTheResource() throws Exception {
        var user = register(newPerson());
        var consent = mvc.perform(
                        authorize("northline-mcp", "merchant mcp", RESOURCE).session(user.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        var code = consentTo("northline-mcp", consent, user.session(), List.of("merchant", "mcp"));

        mvc.perform(tokenRequest("northline-mcp", code, "http://localhost:8080/mcp/docs"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("invalid_target"));
    }

    @Test
    void singleFactorSession_isSentBackForASecondFactor() throws Exception {
        var user = register(newPerson());
        var singleFactor = UsernamePasswordAuthenticationToken.authenticated(
                user.userId(),
                null,
                List.of(
                        new SimpleGrantedAuthority("ROLE_USER"),
                        FactorGrantedAuthority.withAuthority(Factor.PHONE_OTP.authority())
                                .issuedAt(clock.instant())
                                .build()));

        var location = mvc.perform(authorize("northline-mcp", "merchant mcp", RESOURCE)
                        .session(new MockHttpSession())
                        .with(authentication(singleFactor)))
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).doesNotStartWith("http://127.0.0.1");
    }

    @Test
    void clientIdMetadataDocument_registersAPublicPkceClient() throws Exception {
        var clientId = DOCUMENTS.baseUrl() + "/agents/test-agent.json";
        DOCUMENTS.stubFor(WireMock.get(urlEqualTo("/agents/test-agent.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"client_id":"%s","client_name":"Test Agent",
                                 "redirect_uris":["http://127.0.0.1/callback"],
                                 "grant_types":["authorization_code"],"token_endpoint_auth_method":"none"}""".formatted(clientId))));
        var user = register(newPerson());

        var consent = mvc.perform(authorize(clientId, "merchant mcp", RESOURCE).session(user.session()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString();
        // The consent page names the agent by its URL (the host is what the person can check).
        assertThat(consent).contains(clientId);
        var code = consentTo(clientId, consent, user.session(), List.of("merchant", "mcp"));
        var access = token(clientId, code, RESOURCE);

        assertThat(JsonPath.<List<String>>read(access, "$.aud")).contains(RESOURCE);
        assertThat(JsonPath.<String>read(access, "$.client_id")).isEqualTo(clientId);
    }

    @Test
    void clientIdMetadataDocument_withAnotherClientId_isRefused() throws Exception {
        var clientId = DOCUMENTS.baseUrl() + "/agents/impostor.json";
        DOCUMENTS.stubFor(WireMock.get(urlEqualTo("/agents/impostor.json"))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody("""
                                {"client_id":"https://claude.ai/oauth/mcp-oauth-client-metadata","client_name":"Claude",
                                 "redirect_uris":["http://127.0.0.1/callback"]}""")));
        var user = register(newPerson());

        mvc.perform(authorize(clientId, "merchant mcp", RESOURCE).session(user.session()))
                .andExpect(status().isBadRequest());
    }

    private static MockHttpServletRequestBuilder authorize(String clientId, String scope, String resource)
            throws Exception {
        var challenge = Base64.getUrlEncoder()
                .withoutPadding()
                .encodeToString(
                        MessageDigest.getInstance("SHA-256").digest(VERIFIER.getBytes(StandardCharsets.US_ASCII)));
        return get("/oauth2/authorize")
                .accept(MediaType.TEXT_HTML)
                .queryParam("response_type", "code")
                .queryParam("client_id", clientId)
                .queryParam("scope", scope)
                .queryParam("redirect_uri", REDIRECT)
                .queryParam("state", "agent-state")
                .queryParam("resource", resource)
                .queryParam("code_challenge", challenge)
                .queryParam("code_challenge_method", "S256");
    }

    /** Submits the consent page as the person would, ticking {@code scopes}; returns the authorization code. */
    private String consentTo(String clientId, String page, MockHttpSession session, List<String> scopes)
            throws Exception {
        var matcher = Pattern.compile("name=\"state\" value=\"([^\"]+)\"").matcher(page);
        assertThat(matcher.find()).as("consent page carries the state").isTrue();
        var request = post("/oauth2/authorize")
                .session(session)
                .with(csrf())
                .param("client_id", clientId)
                .param("state", matcher.group(1));
        for (var scope : scopes) {
            request.param("scope", scope);
        }
        var location = mvc.perform(request)
                .andExpect(status().is3xxRedirection())
                .andReturn()
                .getResponse()
                .getRedirectedUrl();
        assertThat(location).startsWith("http://127.0.0.1:43123/callback");
        var params = UriComponentsBuilder.fromUriString(location).build().getQueryParams();
        assertThat(params.getFirst("state")).isEqualTo("agent-state");
        var code = params.getFirst("code");
        assertThat(code).isNotBlank();
        return code;
    }

    private MockHttpServletRequestBuilder tokenRequest(String clientId, String code, String resource) {
        return post("/oauth2/token")
                .param("grant_type", "authorization_code")
                .param("client_id", clientId)
                .param("code", code)
                .param("redirect_uri", REDIRECT)
                .param("code_verifier", VERIFIER)
                .param("resource", resource);
    }

    /** Exchanges the code as a public client and returns the access token's claims (JSON). */
    private String token(String clientId, String code, String resource) throws Exception {
        var body = mvc.perform(tokenRequest(clientId, code, resource))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.refresh_token").doesNotExist())
                .andReturn()
                .getResponse()
                .getContentAsString();
        String jwt = JsonPath.read(body, "$.access_token");
        return new String(Base64.getUrlDecoder().decode(jwt.split("\\.")[1]), StandardCharsets.UTF_8);
    }
}
