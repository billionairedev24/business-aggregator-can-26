package ca.northline.mcp;

import ca.northline.support.SharedPostgres;
import ca.northline.support.TestData;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.ECDSASigner;
import com.nimbusds.jose.jwk.Curve;
import com.nimbusds.jose.jwk.ECKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.gen.ECKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.proc.SecurityContext;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import java.io.IOException;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * S-127 test base: the api on a real port (the MCP tools call back to it over HTTP, as in production) with the MCP
 * tools on, and access tokens signed like northline-auth's (ES256, issuer, {@code aud} with {@code northline-api} and,
 * for agents, the MCP server's resource URI) by a test key the api trusts.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@ActiveProfiles("test")
@Import({TestData.class, McpTestServer.Keys.class})
abstract class McpTestServer {

    static final String ISSUER = "http://auth.mcp-test.invalid";
    static final int PORT = freePort();
    static final String BASE = "http://localhost:" + PORT;
    static final String RESOURCE = BASE + "/mcp";
    static final ECKey KEY = newKey();

    @ServiceConnection
    static final PostgreSQLContainer POSTGRES = SharedPostgres.INSTANCE;

    @DynamicPropertySource
    static void mcp(DynamicPropertyRegistry registry) {
        registry.add("server.port", () -> PORT);
        registry.add("springdoc.ai.mcp.enabled", () -> "true");
        registry.add("springdoc.pre-loading-enabled", () -> "true");
        registry.add("springdoc.ai.mcp.base-url", () -> BASE);
        registry.add("northline.mcp.resource", () -> RESOURCE);
        registry.add("northline.mcp.authorization-server", () -> ISSUER);
        registry.add("northline.docs.enabled", () -> "false"); // as in prod: the model exists, nothing publishes it
    }

    @Autowired
    protected TestData data;

    /** northline-auth's validation, with the test key: signature, issuer, time, audience {@code northline-api}. */
    @TestConfiguration(proxyBeanMethods = false)
    static class Keys {
        @Bean
        @Primary
        JwtDecoder testJwtDecoder() throws Exception {
            var decoder = NimbusJwtDecoder.withJwkSource(
                            new ImmutableJWKSet<SecurityContext>(new JWKSet(KEY.toPublicJWK())))
                    .jwsAlgorithm(org.springframework.security.oauth2.jose.jws.SignatureAlgorithm.ES256)
                    .build();
            decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(
                    JwtValidators.createDefaultWithIssuer(ISSUER),
                    new JwtClaimValidator<List<String>>("aud", aud -> aud != null && aud.contains("northline-api"))));
            return decoder;
        }
    }

    /** An access token as northline-auth issues it to the MCP client after a sign-in with a second factor. */
    static String agentToken(String userId, String scope, String... merchants) {
        return token(
                userId, scope, "mfa", List.of("northline-mcp", "northline-api", RESOURCE), "northline-mcp", merchants);
    }

    static String token(
            String subject, String scope, String acr, List<String> audience, String clientId, String... merchants) {
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(subject)
                    .audience(audience)
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))))
                    .claim("scope", scope)
                    .claim("client_id", clientId)
                    .claim("merchants", List.of(merchants));
            if (acr != null) {
                claims.claim("acr", acr);
            }
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims.build());
            jwt.sign(new ECDSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    static String partnerToken(String partner, String scope, String... merchants) {
        try {
            var now = Instant.now();
            var claims = new JWTClaimsSet.Builder()
                    .issuer(ISSUER)
                    .subject(partner)
                    .audience(List.of(partner, "northline-api", RESOURCE))
                    .issueTime(Date.from(now))
                    .expirationTime(Date.from(now.plus(Duration.ofMinutes(10))))
                    .claim("scope", scope)
                    .claim("client_id", partner)
                    .claim("roles", List.of("partner"))
                    .claim("merchants", List.of(merchants))
                    .build();
            var jwt = new SignedJWT(
                    new JWSHeader.Builder(JWSAlgorithm.ES256)
                            .keyID(KEY.getKeyID())
                            .build(),
                    claims);
            jwt.sign(new ECDSASigner(KEY));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    /** The MCP Java SDK's client over Streamable HTTP, sending {@code token} on every request. */
    static McpSyncClient client(String token) {
        var request = HttpRequest.newBuilder().header("Authorization", "Bearer " + token);
        var transport = HttpClientStreamableHttpTransport.builder(BASE)
                .endpoint("/mcp")
                .requestBuilder(request)
                .build();
        return McpClient.sync(transport).requestTimeout(Duration.ofSeconds(30)).build();
    }

    /** A plain HTTP call to the api. */
    static HttpResponse<String> http(String method, String path, String token, String body, Map<String, String> headers)
            throws IOException, InterruptedException {
        var builder = HttpRequest.newBuilder(URI.create(BASE + path))
                .method(
                        method,
                        body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body))
                .header("Content-Type", "application/json");
        if (token != null) {
            builder.header("Authorization", "Bearer " + token);
        }
        headers.forEach(builder::header);
        try (var client = HttpClient.newHttpClient()) {
            return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private static ECKey newKey() {
        try {
            return new ECKeyGenerator(Curve.P_256).keyID("mcp-test").generate();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
