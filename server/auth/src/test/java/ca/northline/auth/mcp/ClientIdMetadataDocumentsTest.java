package ca.northline.auth.mcp;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.InMemoryRegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import tools.jackson.databind.json.JsonMapper;

/** S-127: what a Client ID Metadata Document must look like, and where it may be fetched from (no SSRF). */
class ClientIdMetadataDocumentsTest {

    private static final WireMockServer SERVER = new WireMockServer(options().dynamicPort());

    @BeforeAll
    static void start() {
        SERVER.start();
    }

    @AfterAll
    static void stop() {
        SERVER.stop();
    }

    @Test
    void validDocument_registersAPublicPkceClientWithTheMcpScopes() {
        var url = serve("{\"client_id\":\"%s\",\"client_name\":\"Agent\",\"redirect_uris\":[\"http://127.0.0.1/cb\","
                + "\"https://agent.example/cb\",\"com.example.agent:/cb\"]}");

        var client = documents(true, List.of()).findByClientId(url);

        assertThat(client).isNotNull();
        assertThat(client.getClientAuthenticationMethods()).containsExactly(ClientAuthenticationMethod.NONE);
        assertThat(client.getAuthorizationGrantTypes()).containsExactly(AuthorizationGrantType.AUTHORIZATION_CODE);
        assertThat(client.getClientSettings().isRequireProofKey()).isTrue();
        assertThat(client.getClientSettings().isRequireAuthorizationConsent()).isTrue();
        assertThat(client.getScopes()).doesNotContain("mcp.ops");
        assertThat(client.getId()).startsWith("cimd-");
    }

    @Test
    void loopbackHost_withoutAllowInsecure_isRefused() {
        var url =
                serve("{\"client_id\":\"%s\",\"client_name\":\"Agent\",\"redirect_uris\":[\"https://a.example/cb\"]}");

        assertThat(documents(false, List.of()).findByClientId(url)).isNull();
    }

    @Test
    void hostOutsideTheAllowList_isRefused() {
        var url =
                serve("{\"client_id\":\"%s\",\"client_name\":\"Agent\",\"redirect_uris\":[\"https://a.example/cb\"]}");

        assertThat(documents(true, List.of("claude.ai")).findByClientId(url)).isNull();
    }

    @Test
    void invalidDocuments_areRefused() {
        var docs = documents(true, List.of());
        assertThat(docs.findByClientId(serve("{\"client_id\":\"https://other.example/x\",\"client_name\":\"A\","
                        + "\"redirect_uris\":[\"https://a.example/cb\"]}")))
                .as("client_id differs from the URL")
                .isNull();
        assertThat(docs.findByClientId(serve("{\"client_id\":\"%s\",\"client_name\":\"A\","
                        + "\"redirect_uris\":[\"http://evil.example/cb\"]}")))
                .as("plain http redirect off loopback")
                .isNull();
        assertThat(docs.findByClientId(serve("{\"client_id\":\"%s\",\"client_name\":\"A\","
                        + "\"redirect_uris\":[\"https://a.example/cb\"],\"token_endpoint_auth_method\":"
                        + "\"client_secret_basic\"}")))
                .as("confidential client")
                .isNull();
        assertThat(docs.findByClientId(serve("{\"client_id\":\"%s\",\"client_name\":\"A\","
                        + "\"redirect_uris\":[\"https://a.example/cb\"],\"grant_types\":[\"client_credentials\"]}")))
                .as("client credentials grant")
                .isNull();
        assertThat(docs.findByClientId(serve("{\"client_id\":\"%s\",\"redirect_uris\":[\"https://a.example/cb\"]}")))
                .as("no name")
                .isNull();
    }

    @Test
    void ordinaryClientIds_goToTheRegistrations() {
        var registered = RegisteredClient.withId("1")
                .clientId("studio-bff")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://studio.example/cb")
                .build();
        var docs = new ClientIdMetadataDocuments(
                new InMemoryRegisteredClientRepository(registered),
                properties(true, List.of()),
                JsonMapper.builder().build(),
                Clock.systemUTC());

        assertThat(docs.findByClientId("studio-bff")).isEqualTo(registered);
    }

    private static String serve(String template) {
        var path = "/clients/" + UUID.randomUUID() + ".json";
        var url = SERVER.baseUrl() + path;
        SERVER.stubFor(WireMock.get(urlEqualTo(path))
                .willReturn(aResponse()
                        .withHeader("Content-Type", "application/json")
                        .withBody(template.contains("%s") ? template.formatted(url) : template)));
        return url;
    }

    private static ClientIdMetadataDocuments documents(boolean allowInsecure, List<String> allowedHosts) {
        var placeholder = RegisteredClient.withId("placeholder")
                .clientId("placeholder")
                .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .redirectUri("https://placeholder.example/cb")
                .build();
        return new ClientIdMetadataDocuments(
                new InMemoryRegisteredClientRepository(placeholder),
                properties(allowInsecure, allowedHosts),
                JsonMapper.builder().build(),
                Clock.systemUTC());
    }

    private static McpAuthProperties.MetadataDocuments properties(boolean allowInsecure, List<String> allowedHosts) {
        return new McpAuthProperties.MetadataDocuments(
                true,
                List.of("openid", "profile", "merchant", "mcp", "mcp.write"),
                Duration.ofHours(1),
                Duration.ofMinutes(10),
                Duration.ofSeconds(5),
                5120,
                allowedHosts,
                allowInsecure);
    }
}
